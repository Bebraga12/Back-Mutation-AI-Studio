package com.mutation.mutation_ai_studio.adapters.in.web;

import com.mutation.mutation_ai_studio.adapters.in.web.project.ProjectCatalogService;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.AiTestClassResult;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.AiTestRunAcceptedResponse;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.AiTestRunStatusResponse;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.ApiDashboardData;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.ApiDiffLine;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.ApiDiffSnapshot;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.ApiGaugeMetric;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.ApiInsightFeedback;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.ApiMavenDetectionResult;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.ApiProject;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.ApiProjectClass;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.CreateProjectRequest;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.MutationRunAcceptedResponse;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.MutationRunStatusResponse;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.StartAiTestRunRequest;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.StartMutationRunRequest;
import com.mutation.mutation_ai_studio.application.port.in.CreateTestPromptUseCase;
import com.mutation.mutation_ai_studio.application.port.in.ExecuteGeneratedTestBatchUseCase;
import com.mutation.mutation_ai_studio.application.port.in.GenerateTestFromPromptUseCase;
import com.mutation.mutation_ai_studio.application.port.in.ScanProjectUseCase;
import com.mutation.mutation_ai_studio.application.port.out.SelectionRepositoryPort;
import com.mutation.mutation_ai_studio.application.port.out.TestPromptRepositoryPort;
import com.mutation.mutation_ai_studio.domain.model.ClassTestPrompt;
import com.mutation.mutation_ai_studio.domain.model.GeneratedTestBatch;
import com.mutation.mutation_ai_studio.domain.model.GeneratedTestExecutionResult;
import com.mutation.mutation_ai_studio.domain.model.JavaClassCandidate;
import com.mutation.mutation_ai_studio.domain.model.SelectionSnapshot;
import com.mutation.mutation_ai_studio.domain.model.TestPromptBatch;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
public class InMemoryWorkspaceApiService {

    private static final int RUN_TIMEOUT_MINUTES = 20;
    private static final int MAX_OUTPUT_LINES = 180;

    private static final ApiDiffSnapshot EMPTY_DIFF_SNAPSHOT = new ApiDiffSnapshot(
            "Antes - sem execucao",
            "Depois - sem execucao",
            List.of(),
            List.of());

    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    private final ScanProjectUseCase scanProjectUseCase;
    private final CreateTestPromptUseCase createTestPromptUseCase;
    private final GenerateTestFromPromptUseCase generateTestFromPromptUseCase;
    private final ExecuteGeneratedTestBatchUseCase executeGeneratedTestBatchUseCase;
    private final SelectionRepositoryPort selectionRepositoryPort;
    private final TestPromptRepositoryPort testPromptRepositoryPort;
    private final ProjectCatalogService projectCatalogService;
    private final PitestSummaryCacheRepository pitestSummaryCacheRepository;
    private final DashboardStateCacheRepository dashboardStateCacheRepository;
    private final PitRunnerClient pitRunnerClient;

    private final Map<String, WorkspaceState> workspaceByProjectId = new ConcurrentHashMap<>();
    private final Map<String, MutationRunState> mutationRunsByRunId = new ConcurrentHashMap<>();
    private final Map<String, AiTestRunState> aiTestRunsByRunId = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final MavenResolver mavenResolver = new MavenResolver();
    private final PitestReportLoader pitestReportLoader = new PitestReportLoader();
    private final CommandExecutor commandExecutor = new CommandExecutor(RUN_TIMEOUT_MINUTES, MAX_OUTPUT_LINES);

    public InMemoryWorkspaceApiService(ScanProjectUseCase scanProjectUseCase,
                                       CreateTestPromptUseCase createTestPromptUseCase,
                                       GenerateTestFromPromptUseCase generateTestFromPromptUseCase,
                                       ExecuteGeneratedTestBatchUseCase executeGeneratedTestBatchUseCase,
                                       SelectionRepositoryPort selectionRepositoryPort,
                                       TestPromptRepositoryPort testPromptRepositoryPort,
                                       ProjectCatalogService projectCatalogService,
                                       PitestSummaryCacheRepository pitestSummaryCacheRepository,
                                       DashboardStateCacheRepository dashboardStateCacheRepository,
                                       PitRunnerClient pitRunnerClient) {
        this.scanProjectUseCase = scanProjectUseCase;
        this.createTestPromptUseCase = createTestPromptUseCase;
        this.generateTestFromPromptUseCase = generateTestFromPromptUseCase;
        this.executeGeneratedTestBatchUseCase = executeGeneratedTestBatchUseCase;
        this.selectionRepositoryPort = selectionRepositoryPort;
        this.testPromptRepositoryPort = testPromptRepositoryPort;
        this.projectCatalogService = projectCatalogService;
        this.pitestSummaryCacheRepository = pitestSummaryCacheRepository;
        this.dashboardStateCacheRepository = dashboardStateCacheRepository;
        this.pitRunnerClient = pitRunnerClient;
    }

    @PostConstruct
    void loadPersistedProjects() {
        projectCatalogService.listProjects().forEach(this::registerProject);
    }

    public List<ApiProject> listProjects() {
        return projectCatalogService.listProjects();
    }

    public Optional<ApiProject> findProject(String projectId) {
        return projectCatalogService.findProject(projectId);
    }

    public synchronized ApiProject createProject(CreateProjectRequest request) {
        ApiProject project = projectCatalogService.createProject(request);
        registerProject(project);
        return project;
    }

    public synchronized boolean deleteProject(String projectId) {
        boolean removed = projectCatalogService.deleteProject(projectId);
        if (removed) {
            unregisterProject(projectId);
        }
        return removed;
    }

    public synchronized void registerProject(ApiProject project) {
        workspaceByProjectId.computeIfAbsent(project.id(), ignored -> restoreWorkspaceState(project));
    }

    public synchronized void unregisterProject(String projectId) {
        workspaceByProjectId.remove(normalize(projectId));
    }

    public synchronized MutationRunAcceptedResponse startMutationRun(StartMutationRunRequest request) {
        String projectId = normalize(request.projectId());
        WorkspaceState state = workspaceByProjectId.get(projectId);
        if (state == null) {
            throw new IllegalArgumentException("Projeto nao encontrado: " + projectId);
        }

        List<String> selectedClasses = request.classes() == null
                ? List.of()
                : request.classes().stream()
                        .map(this::normalize)
                        .filter(value -> !value.isBlank())
                        .distinct()
                        .toList();

        if (selectedClasses.isEmpty()) {
            throw new IllegalArgumentException("Selecione ao menos uma classe para executar.");
        }

        String mavenPath = normalize(request.mavenPath());
        if (mavenPath.isBlank()) {
            mavenPath = normalize(state.project().mavenPath());
        }

        if (mavenPath.isBlank()) {
            throw new IllegalArgumentException("Informe ou detecte o Maven antes de executar.");
        }

        Path mavenBinary = Paths.get(mavenPath).toAbsolutePath().normalize();
        if (!Files.exists(mavenBinary) || !Files.isRegularFile(mavenBinary)) {
            throw new IllegalArgumentException("Caminho Maven nao encontrado: " + mavenPath);
        }

        String repositoryPath = normalize(state.project().repositoryPath());
        if (repositoryPath.isBlank()) {
            throw new IllegalArgumentException("Informe o caminho do repositorio antes de executar.");
        }

        Path repositoryRoot = Paths.get(repositoryPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(repositoryRoot)) {
            throw new IllegalArgumentException("Repositorio nao encontrado: " + repositoryRoot);
        }

        String runId = "run-" + System.currentTimeMillis();

        ApiProject projectWithMaven = new ApiProject(
                state.project().id(),
                state.project().name(),
                state.project().repositoryPath(),
                mavenBinary.toString(),
                state.project().lastMutationScore(),
                "Execucao em fila");

        workspaceByProjectId.put(projectId, new WorkspaceState(
                projectWithMaven,
                state.classOptions(),
                state.gaugeMetrics(),
                state.insights(),
                state.diffSnapshot()));
        projectCatalogService.saveProject(projectWithMaven);

        MutationRunState runState = MutationRunState.queued(runId, projectId, mavenBinary.toString(), selectedClasses);
        mutationRunsByRunId.put(runId, runState);
        executor.submit(() -> executeMutationRun(runId));

        return new MutationRunAcceptedResponse(
                runId,
                "queued",
                "Rodada recebida. Execucao iniciada em segundo plano.",
                false);
    }

    public Optional<MutationRunStatusResponse> findMutationRun(String runId) {
        return Optional.ofNullable(mutationRunsByRunId.get(normalize(runId)))
                .map(MutationRunState::toResponse);
    }

    public Optional<List<ApiProjectClass>> findClasses(String projectId) {
        return Optional.ofNullable(workspaceByProjectId.get(projectId))
                .map(state -> scanClassesFromProject(state.project()));
    }

    public AiTestRunAcceptedResponse startAiTestRun(String projectId, StartAiTestRunRequest request) {
        WorkspaceState state = workspaceByProjectId.get(projectId);
        if (state == null) {
            throw new IllegalArgumentException("Projeto nao encontrado: " + projectId);
        }

        String repositoryPath = normalize(state.project().repositoryPath());
        if (repositoryPath.isBlank()) {
            throw new IllegalArgumentException("Informe o caminho do repositorio antes de gerar.");
        }

        Path projectRoot = Paths.get(repositoryPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(projectRoot)) {
            throw new IllegalArgumentException("Repositorio nao encontrado: " + projectRoot);
        }

        Set<String> requestedFqns = request.classes().stream()
                .map(this::normalize)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());

        List<JavaClassCandidate> allCandidates = scanProjectUseCase.scan(projectRoot);
        List<JavaClassCandidate> selected = allCandidates.stream()
                .filter(c -> requestedFqns.contains(normalize(c.fullyQualifiedName())))
                .toList();

        if (selected.isEmpty()) {
            throw new IllegalArgumentException("Nenhuma classe valida encontrada para as FQNs informadas.");
        }

        if (!isOllamaRunning()) {
            throw new IllegalArgumentException(
                    "Ollama nao esta rodando em " + ollamaBaseUrl + ". Execute `ollama serve` e tente novamente.");
        }

        selectionRepositoryPort.save(projectRoot, new SelectionSnapshot(
                projectRoot.toString(), Instant.now(), selected.size(), selected));

        String runId = "ai-run-" + System.currentTimeMillis();
        AiTestRunState runState = AiTestRunState.queued(runId, projectId, selected.size());
        aiTestRunsByRunId.put(runId, runState);

        executor.submit(() -> executeAiTestGeneration(runId, runState, projectRoot));

        return new AiTestRunAcceptedResponse(runId, "queued",
                "Geracao iniciada para " + selected.size() + " classe(s). Acompanhe pelo runId.");
    }

    public Optional<AiTestRunStatusResponse> findAiTestRun(String runId) {
        return Optional.ofNullable(aiTestRunsByRunId.get(normalize(runId)))
                .map(AiTestRunState::toResponse);
    }

    private void executeAiTestGeneration(String runId, AiTestRunState runState, Path projectRoot) {
        try {
            runState.markRunning("Criando prompts e chamando o modelo Ollama...");

            TestPromptBatch batch = createTestPromptUseCase.create(projectRoot);

            List<ClassTestPrompt> savedPrompts = new ArrayList<>();
            for (ClassTestPrompt prompt : batch.prompts()) {
                Path savedPath = testPromptRepositoryPort.save(projectRoot, prompt, batch.createdAt());
                savedPrompts.add(new ClassTestPrompt(
                        prompt.className(), prompt.fullyQualifiedName(), prompt.relativePath(),
                        prompt.dependencies(), prompt.analysis(), prompt.sourceCode(),
                        prompt.prompt(), savedPath));
            }

            TestPromptBatch savedBatch = new TestPromptBatch(
                    batch.projectRoot(), batch.createdAt(), batch.totalSelected(), savedPrompts);

            runState.markRunning("Gerando testes com Ollama...");
            GeneratedTestBatch generatedBatch = generateTestFromPromptUseCase.generate(projectRoot, savedBatch);

            runState.markRunning("Compilando e executando testes gerados...");
            List<GeneratedTestExecutionResult> executionResults =
                    executeGeneratedTestBatchUseCase.execute(projectRoot, generatedBatch);

            List<AiTestClassResult> results = executionResults.stream()
                    .map(r -> new AiTestClassResult(
                            r.candidate().className(),
                            r.candidate().fullyQualifiedName(),
                            r.feedback().passed(),
                            r.preservedPath() != null ? relativize(projectRoot, r.preservedPath()) : "",
                            r.feedback().errors().stream().limit(3).toList()))
                    .toList();

            long passed = results.stream().filter(AiTestClassResult::passed).count();
            String summary = passed + "/" + results.size() + " testes aprovados pelo compilador e executor.";

            if (passed > 0) {
                String pitSummary = refreshPitMetricsAfterAiGeneration(runState, projectRoot);
                if (!pitSummary.isBlank()) {
                    summary = summary + " " + pitSummary;
                }
            }

            runState.markCompleted(summary, results);
            persistAiGenerationDuration(projectRoot, runState);

        } catch (Exception ex) {
            runState.markFailed("Falha na geracao: " + normalize(ex.getMessage()));
        }
    }

    private void persistAiGenerationDuration(Path projectRoot, AiTestRunState runState) {
        Long durationMs = runState.currentDurationMs();
        if (durationMs == null) {
            return;
        }

        Path repositoryRoot = projectRoot.toAbsolutePath().normalize();
        pitestSummaryCacheRepository.updateDuration(repositoryRoot, durationMs);
        dashboardStateCacheRepository.updateDuration(repositoryRoot, durationMs);
    }

    private String refreshPitMetricsAfterAiGeneration(AiTestRunState runState, Path projectRoot) {
        WorkspaceState workspaceState = workspaceByProjectId.get(runState.projectId());
        if (workspaceState == null) {
            return "";
        }

        String mavenPath = normalize(workspaceState.project().mavenPath());
        if (mavenPath.isBlank()) {
            return "Maven nao detectado; baseline PIT nao foi atualizado.";
        }

        try {
            Path repositoryRoot = projectRoot.toAbsolutePath().normalize();
            projectCatalogService.ensurePitestPluginInstalled(repositoryRoot.toString());

            Path mavenBinary = Paths.get(mavenPath).toAbsolutePath().normalize();
            if (!Files.exists(mavenBinary) || !Files.isRegularFile(mavenBinary)) {
                return "Caminho Maven invalido; baseline PIT nao foi atualizado.";
            }

            runState.markRunning("Validando suite completa antes de atualizar o baseline PIT...");
            CommandExecutionResult preflight = executeTestPreflightWithFallback(repositoryRoot, mavenBinary.toString());
            if (preflight.exitCode() != 0 || preflight.timedOut()) {
                updateWorkspaceAfterRun(workspaceState.project().id(), workspaceState, preflight, Optional.empty(), true);
                return "PIT nao atualizado porque a suite completa falhou no preflight.";
            }

            runState.markRunning("Gerando metricas PIT com os testes aprovados...");
            CommandExecutionResult execution = executePitWithFallback(repositoryRoot, mavenBinary.toString(), List.of());
            Optional<PitestMetrics> pitMetrics = pitestReportLoader.loadLatestMetrics(repositoryRoot);
            updateWorkspaceAfterRun(workspaceState.project().id(), workspaceState, execution, pitMetrics, true);

            if (pitMetrics.isPresent()) {
                return "Mutation Score atualizado para " + pitMetrics.get().mutationScore() + "%.";
            }

            return execution.exitCode() == 0
                    ? "PIT executado, mas o relatorio nao foi encontrado."
                    : "PIT nao conseguiu atualizar o relatorio.";
        } catch (Exception exception) {
            return "Falha ao atualizar baseline PIT: " + normalize(exception.getMessage()) + ".";
        }
    }

    private boolean isOllamaRunning() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(ollamaBaseUrl + "/api/tags").openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.connect();
            return conn.getResponseCode() < 500;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String relativize(Path projectRoot, Path absolute) {
        try {
            return projectRoot.relativize(absolute).toString();
        } catch (IllegalArgumentException ignored) {
            return absolute.toString();
        }
    }

    public Optional<ApiMavenDetectionResult> detectMaven(String projectId) {
        return Optional.ofNullable(workspaceByProjectId.get(projectId))
                .map(state -> {
                    ApiMavenDetectionResult result =
                            mavenResolver.resolve(state.project().mavenPath(), state.project().repositoryPath());
                    if (result.found()) {
                        projectCatalogService.ensurePitestPluginInstalled(state.project().repositoryPath());

                        ApiProject projectWithMaven = new ApiProject(
                                state.project().id(),
                                state.project().name(),
                                state.project().repositoryPath(),
                                result.path(),
                                state.project().lastMutationScore(),
                                state.project().updatedAt());

                        WorkspaceState updatedState = new WorkspaceState(
                                projectWithMaven,
                                state.classOptions(),
                                state.gaugeMetrics(),
                                state.insights(),
                                state.diffSnapshot());

                        workspaceByProjectId.put(projectId, updatedState);
                        projectCatalogService.saveProject(projectWithMaven);
                        executor.submit(() -> hydrateMetricsAfterMavenDetection(projectId, updatedState, result.path()));
                    }
                    return result;
                });
    }

    public Optional<ApiDashboardData> findDashboard(String projectId) {
        return Optional.ofNullable(workspaceByProjectId.get(projectId))
                .map(state -> {
                    if (state.gaugeMetrics().isEmpty() && state.insights().isEmpty()) {
                        WorkspaceState restoredState = restoreWorkspaceState(state.project());
                        workspaceByProjectId.put(projectId, restoredState);
                        state = restoredState;
                    }

                    if (state.project().lastMutationScore() <= 0
                            && state.gaugeMetrics().isEmpty()
                            && state.insights().isEmpty()) {
                        return new ApiDashboardData(List.of(), List.of(), EMPTY_DIFF_SNAPSHOT, null);
                    }

                    Long durationMs = loadPersistedDurationMs(state.project());

                    return new ApiDashboardData(
                            state.gaugeMetrics(),
                            state.insights(),
                            state.diffSnapshot(),
                            durationMs);
                });
    }

    private void executeMutationRun(String runId) {
        MutationRunState runState = mutationRunsByRunId.get(runId);
        if (runState == null) {
            return;
        }

        WorkspaceState workspaceState = workspaceByProjectId.get(runState.projectId());
        if (workspaceState == null) {
            runState.markFailed("Projeto removido antes da execucao iniciar.", null);
            return;
        }

        Path repositoryRoot = Paths.get(normalize(workspaceState.project().repositoryPath())).toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(repositoryRoot)) {
            runState.markFailed("Repositorio nao encontrado: " + repositoryRoot, null);
            return;
        }

        boolean pitConfigured = pitestReportLoader.hasPitestPlugin(repositoryRoot);
        List<String> command = pitConfigured
                ? buildPitRunnerPreview(runState.mavenPath(), runState.classes())
                : buildRunCommand(runState.mavenPath(), runState.classes(), false);
        runState.markRunning(
                pitConfigured
                        ? "Solicitando execucao PIT ao runner local."
                        : "Executando Maven no repositorio selecionado.",
                command);

        CommandExecutionResult execution;
        try {
            if (pitConfigured) {
                CommandExecutionResult preflight = executeTestPreflightWithFallback(repositoryRoot, runState.mavenPath());
                if (preflight.exitCode() != 0 || preflight.timedOut()) {
                    runState.markFailed("Suite base de testes falhou antes do PIT.", preflight);
                    updateWorkspaceAfterRun(workspaceState.project().id(), workspaceState, preflight, Optional.empty(), true);
                    return;
                }
                execution = executePitWithFallback(repositoryRoot, runState.mavenPath(), runState.classes());
            } else {
                execution = commandExecutor.execute(repositoryRoot, command);
            }
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            runState.markFailed("Execucao interrompida.", null);
            return;
        } catch (Exception exception) {
            runState.markFailed("Falha ao iniciar processo Maven: " + normalize(exception.getMessage()), null);
            return;
        }

        if (execution.timedOut()) {
            runState.markFailed("Tempo limite excedido apos " + RUN_TIMEOUT_MINUTES + " minutos.", execution);
            updateWorkspaceAfterRun(workspaceState.project().id(), workspaceState, execution, Optional.empty(), pitConfigured);
            return;
        }

        if (execution.exitCode() != 0) {
            String errorLine = commandExecutor.extractMeaningfulErrorLine(execution.outputLines());
            String message = errorLine.isBlank()
                    ? "Execucao Maven finalizou com erro (exit code " + execution.exitCode() + ")."
                    : "Execucao Maven falhou: " + errorLine;
            runState.markFailed(message, execution);
            updateWorkspaceAfterRun(workspaceState.project().id(), workspaceState, execution, Optional.empty(), pitConfigured);
            return;
        }

        Optional<PitestMetrics> pitMetrics = pitestReportLoader.loadLatestMetrics(repositoryRoot);
        String successMessage;
        if (pitMetrics.isPresent()) {
            successMessage = "Execucao concluida. Mutation Score atual: " + pitMetrics.get().mutationScore() + "%.";
        } else if (pitConfigured) {
            successMessage = "Execucao concluida, mas o relatorio PIT nao foi encontrado em target/pit-reports.";
        } else {
            successMessage = "Execucao de testes concluida. Configure o plugin PIT para gerar Mutation Score real.";
        }

        runState.markCompleted(successMessage, execution);
        updateWorkspaceAfterRun(workspaceState.project().id(), workspaceState, execution, pitMetrics, pitConfigured);
    }

    private synchronized void updateWorkspaceAfterRun(
            String projectId,
            WorkspaceState snapshot,
            CommandExecutionResult execution,
            Optional<PitestMetrics> pitMetrics,
            boolean pitConfigured) {
        WorkspaceState currentState = workspaceByProjectId.get(projectId);
        if (currentState == null) {
            return;
        }

        int previousScore = Math.max(0, snapshot.project().lastMutationScore());
        ApiProject currentProject = currentState.project();

        List<ApiGaugeMetric> nextGaugeMetrics = currentState.gaugeMetrics();
        List<ApiInsightFeedback> nextInsights;
        ApiDiffSnapshot nextDiffSnapshot = currentState.diffSnapshot();
        int nextScore = previousScore;

        if (pitMetrics.isPresent()) {
            PitestMetrics metrics = pitMetrics.get();
            nextScore = metrics.mutationScore();
            boolean hasPreviousMetrics = !currentState.gaugeMetrics().isEmpty();

            int currentCoverageRate = metrics.total() > 0
                    ? Math.round(((metrics.total() - metrics.noCoverage()) * 100f) / metrics.total()) : 0;
            int coveredMutants = metrics.total() - metrics.noCoverage();
            int currentSurvivorOfCoveredRate = coveredMutants > 0
                    ? Math.round((metrics.survivorCount() * 100f) / coveredMutants) : 0;

            int previousCoverageRate = resolvePreviousGaugeAfter(
                    currentState.gaugeMetrics(), "coverage-rate", currentCoverageRate);
            int previousSurvivorOfCoveredRate = resolvePreviousGaugeAfter(
                    currentState.gaugeMetrics(), "survivor-of-covered", currentSurvivorOfCoveredRate);

            nextGaugeMetrics = List.of(
                    new ApiGaugeMetric(
                            "mutation-score",
                            "Mutation Score",
                            "Percentual de mutantes eliminados pelo total gerado",
                            hasPreviousMetrics ? previousScore : nextScore,
                            nextScore,
                            "emerald"),
                    new ApiGaugeMetric(
                            "coverage-rate",
                            "Cobertura de mutacao",
                            "Mutantes cobertos por pelo menos um teste",
                            hasPreviousMetrics ? previousCoverageRate : currentCoverageRate,
                            currentCoverageRate,
                            "soft-blue"),
                    new ApiGaugeMetric(
                            "survivor-of-covered",
                            "Sobreviventes cobertos",
                            "Dos cobertos, percentual que os testes nao detectaram",
                            hasPreviousMetrics ? previousSurvivorOfCoveredRate : currentSurvivorOfCoveredRate,
                            currentSurvivorOfCoveredRate,
                            "amber"));

            String recommendation;
            if (metrics.total() == 0) {
                recommendation = "Ajuste targetClasses para classes de negocio com codigo executavel.";
            } else if (metrics.noCoverage() > 0) {
                recommendation = "Aumente cobertura de testes nas classes selecionadas antes de comparar score entre rodadas.";
            } else if (metrics.survivorCount() > 0) {
                recommendation = "Priorize os mutantes sobreviventes em novas assercoes direcionadas.";
            } else {
                recommendation = "Sem mutantes sobreviventes nesta rodada.";
            }

            nextInsights = List.of(
                    new ApiInsightFeedback(
                            "Relatorio PIT processado",
                            "Total de mutantes: " + metrics.total()
                                    + " | Mortos: " + metrics.killed()
                                    + " | Sobreviventes: " + metrics.survivorCount()
                                    + " | Sem cobertura: " + metrics.noCoverage() + ".",
                            "Arquivo analisado: " + metrics.reportFile(),
                            "soft-blue"),
                    new ApiInsightFeedback(
                            metrics.total() == 0
                                    ? "Nenhum mutante gerado"
                                    : (metrics.survivorCount() > 0 ? "Ainda existem sobreviventes"
                                            : "Excelente cobertura nesta rodada"),
                            metrics.total() == 0
                                    ? "O PIT nao encontrou mutacoes para as classes/escopo configurados."
                                    : (metrics.survivorCount() > 0
                                            ? "Foram encontrados mutantes sobreviventes apos a execucao."
                                            : "Todos os mutantes detectados foram eliminados."),
                            recommendation,
                            metrics.total() == 0 ? "soft-blue" : (metrics.survivorCount() > 0 ? "amber" : "emerald")));

            int beforeScore = hasPreviousMetrics ? previousScore : nextScore;
            int beforeCoverage = hasPreviousMetrics ? previousCoverageRate : currentCoverageRate;
            int beforeSurvivorOfCovered = hasPreviousMetrics ? previousSurvivorOfCoveredRate : currentSurvivorOfCoveredRate;
            nextDiffSnapshot = buildPitDiffSnapshot(
                    metrics,
                    beforeScore, beforeCoverage, beforeSurvivorOfCovered,
                    currentCoverageRate, currentSurvivorOfCoveredRate);
        } else {
            boolean executionSucceeded = execution.exitCode() == 0 && !execution.timedOut();
            String detail;
            String recommendation;
            String errorLine = commandExecutor.extractMeaningfulErrorLine(execution.outputLines());
            if (!executionSucceeded) {
                if (errorLine.contains("Runner local PIT indisponivel") || errorLine.contains("runner local indisponivel")) {
                    detail = "A execucao PIT falhou porque o runner local nao respondeu ou nao esta em execucao.";
                    recommendation = "Inicie o runner local com `npm run start:pit-runner` antes de detectar o Maven ou executar a rodada.";
                } else if (errorLine.contains("Suite base de testes falhou")) {
                    detail = "A suite base de testes do projeto falhou antes da geracao das metricas de mutacao.";
                    recommendation = "Corrija os testes quebrados em `mvn test` antes de executar o PIT.";
                } else if (errorLine.contains("Operation not permitted")) {
                    detail = "A execucao PIT falhou porque o ambiente bloqueou a abertura do socket interno usado pelo minion de cobertura.";
                    recommendation = "Execute o backend em um ambiente sem essa restricao de socket local para permitir a geracao do relatorio PIT.";
                } else if (errorLine.contains("Coverage generation minion exited abnormally")) {
                    detail = "A execucao PIT falhou porque o minion de cobertura encerrou de forma anormal antes de gerar as metricas.";
                    recommendation = "Revise a configuracao e os testes do projeto para descobrir por que o minion de cobertura esta abortando.";
                } else {
                    detail = "A execucao de testes falhou antes da geracao de metricas de mutacao.";
                    recommendation = pitConfigured
                            ? "Revise a falha Maven exibida abaixo para permitir que o PIT conclua a geracao do relatorio."
                            : "Configure o plugin PIT no pom.xml para preencher Mutation Score, metricas e diff automaticamente.";
                }
            } else if (pitConfigured) {
                detail = "A execucao terminou, mas nenhum mutations.xml foi encontrado em target/pit-reports.";
                recommendation = "Revise a configuracao do PIT e as classes alvo para garantir que o relatorio seja gerado.";
            } else {
                detail = "Os testes foram executados com sucesso, mas o projeto ainda nao possui plugin PIT configurado.";
                recommendation = "Configure o plugin PIT no pom.xml para preencher Mutation Score, metricas e diff automaticamente.";
            }

            nextInsights = List.of(
                    new ApiInsightFeedback(
                            executionSucceeded
                                    ? "Execucao concluida sem metricas de mutacao"
                                    : "Execucao com falha",
                            detail,
                            recommendation,
                            executionSucceeded ? "amber" : "soft-blue"));

            nextDiffSnapshot = buildNoMetricsDiffSnapshot(execution, pitConfigured);
        }

        ApiProject updatedProject = new ApiProject(
                currentProject.id(),
                currentProject.name(),
                currentProject.repositoryPath(),
                currentProject.mavenPath(),
                nextScore,
                execution.exitCode() == 0 ? "Atualizado agora" : "Execucao com falha");

        workspaceByProjectId.put(projectId, new WorkspaceState(
                updatedProject,
                currentState.classOptions(),
                nextGaugeMetrics,
                nextInsights,
                nextDiffSnapshot));
        projectCatalogService.saveProject(updatedProject);

        Path repositoryRoot = Paths.get(normalize(updatedProject.repositoryPath())).toAbsolutePath().normalize();
        dashboardStateCacheRepository.save(repositoryRoot, new DashboardStateCache(
                nextGaugeMetrics,
                nextInsights,
                nextDiffSnapshot,
                execution.durationMs(),
                java.time.Instant.now().toString()));

        pitMetrics.ifPresent(metrics -> {
            pitestSummaryCacheRepository.save(repositoryRoot, metrics, execution.durationMs());
        });
    }

    private void hydrateMetricsAfterMavenDetection(String projectId, WorkspaceState state, String detectedMavenPath) {
        Path repositoryRoot = Paths.get(normalize(state.project().repositoryPath())).toAbsolutePath().normalize();
        projectCatalogService.ensurePitestPluginInstalled(repositoryRoot.toString());

        WorkspaceState restoredState = restoreWorkspaceState(new ApiProject(
                state.project().id(),
                state.project().name(),
                state.project().repositoryPath(),
                detectedMavenPath,
                state.project().lastMutationScore(),
                state.project().updatedAt()));
        WorkspaceState baselineState = restoredState.gaugeMetrics().isEmpty() ? state : restoredState;
        if (!restoredState.gaugeMetrics().isEmpty()) {
            workspaceByProjectId.put(projectId, restoredState);
            projectCatalogService.saveProject(restoredState.project());
        }

        if (!pitestReportLoader.hasPitestPlugin(repositoryRoot)) {
            return;
        }

        try {
            Path mavenBinary = Paths.get(detectedMavenPath).toAbsolutePath().normalize();
            if (!Files.exists(mavenBinary) || !Files.isRegularFile(mavenBinary)) {
                return;
            }

            WorkspaceState executionState = baselineState.gaugeMetrics().isEmpty()
                    ? resetDashboardStateForFreshDetection(state, detectedMavenPath)
                    : baselineState;

            workspaceByProjectId.put(projectId, executionState);
            projectCatalogService.saveProject(executionState.project());

            CommandExecutionResult preflight = executeTestPreflightWithFallback(repositoryRoot, mavenBinary.toString());
            if (preflight.exitCode() != 0 || preflight.timedOut()) {
                updateWorkspaceAfterRun(projectId, executionState, preflight, Optional.empty(), true);
                return;
            }

            CommandExecutionResult execution = executePitWithFallback(repositoryRoot, mavenBinary.toString(), List.of());
            Optional<PitestMetrics> pitMetrics = pitestReportLoader.loadLatestMetrics(repositoryRoot);

            updateWorkspaceAfterRun(projectId, executionState, execution, pitMetrics, true);
        } catch (Exception ignored) {
            // Baseline oportunista; falha nao deve quebrar deteccao do Maven.
        }
    }

    private WorkspaceState resetDashboardStateForFreshDetection(WorkspaceState state, String detectedMavenPath) {
        ApiProject project = new ApiProject(
                state.project().id(),
                state.project().name(),
                state.project().repositoryPath(),
                detectedMavenPath,
                0,
                "Aguardando baseline PIT");

        return new WorkspaceState(
                project,
                state.classOptions(),
                List.of(),
                List.of(),
                EMPTY_DIFF_SNAPSHOT);
    }

    private int resolvePreviousGaugeAfter(List<ApiGaugeMetric> gauges, String id, int fallback) {
        return gauges.stream()
                .filter(gauge -> id.equals(gauge.id()))
                .map(ApiGaugeMetric::after)
                .findFirst()
                .orElse(fallback);
    }

    private ApiDiffSnapshot buildPitDiffSnapshot(
            PitestMetrics metrics,
            int prevScore, int prevCoverage, int prevSurvivorOfCovered,
            int currentCoverage, int currentSurvivorOfCovered) {

        List<ApiDiffLine> beforeLines = List.of(
                new ApiDiffLine(1, "Mutation Score:          " + prevScore + "%", "context"),
                new ApiDiffLine(2, "Cobertura de mutacao:    " + prevCoverage + "%", "context"),
                new ApiDiffLine(3, "Sobreviventes cobertos:  " + prevSurvivorOfCovered + "%", "context"),
                new ApiDiffLine(4, "Mutantes eliminados:     " + "n/d", "context"),
                new ApiDiffLine(5, "Mutantes sobreviventes:  " + "n/d", "context"),
                new ApiDiffLine(6, "Sem cobertura:           " + "n/d", "context"));

        List<ApiDiffLine> afterLines = List.of(
                new ApiDiffLine(1, "Mutation Score:          " + metrics.mutationScore() + "%",
                        kindForHigherIsBetter(prevScore, metrics.mutationScore())),
                new ApiDiffLine(2, "Cobertura de mutacao:    " + currentCoverage + "%",
                        kindForHigherIsBetter(prevCoverage, currentCoverage)),
                new ApiDiffLine(3, "Sobreviventes cobertos:  " + currentSurvivorOfCovered + "%",
                        kindForLowerIsBetter(prevSurvivorOfCovered, currentSurvivorOfCovered)),
                new ApiDiffLine(4, "Mutantes eliminados:     " + metrics.killed(),
                        metrics.killed() > 0 ? "added" : "context"),
                new ApiDiffLine(5, "Mutantes sobreviventes:  " + metrics.survivorCount(),
                        metrics.survivorCount() > 0 ? "removed" : "added"),
                new ApiDiffLine(6, "Sem cobertura:           " + metrics.noCoverage(),
                        metrics.noCoverage() > 0 ? "removed" : "added"));

        return new ApiDiffSnapshot(
                "Antes - rodada anterior",
                "Depois - resultado PIT",
                beforeLines,
                afterLines);
    }

    private String kindForHigherIsBetter(int before, int after) {
        if (after > before) return "added";
        if (after < before) return "removed";
        return "context";
    }

    private String kindForLowerIsBetter(int before, int after) {
        if (after < before) return "added";
        if (after > before) return "removed";
        return "context";
    }

    private ApiDiffSnapshot buildNoMetricsDiffSnapshot(CommandExecutionResult execution, boolean pitConfigured) {
        String afterStatus = execution.exitCode() == 0 && !execution.timedOut()
                ? "Execucao finalizada sem relatorio de mutacao"
                : "Execucao finalizada com falha";
        String detail;
        if (execution.exitCode() != 0 || execution.timedOut()) {
            String errorLine = commandExecutor.extractMeaningfulErrorLine(execution.outputLines());
            if (errorLine.contains("Runner local PIT indisponivel") || errorLine.contains("runner local indisponivel")) {
                detail = "Falha Maven: runner local PIT indisponivel. Inicie `npm run start:pit-runner`.";
            } else if (errorLine.contains("Suite base de testes falhou")) {
                detail = "Falha Maven: a suite base de testes falhou antes do PIT.";
            } else if (errorLine.contains("Operation not permitted")) {
                detail = "Falha Maven: o PIT nao conseguiu abrir o socket interno do minion de cobertura neste ambiente.";
            } else if (errorLine.contains("Coverage generation minion exited abnormally")) {
                detail = "Falha Maven: o minion de cobertura do PIT encerrou de forma anormal antes de gerar o relatorio.";
            } else if (errorLine.isBlank()) {
                detail = "A execucao Maven falhou antes de gerar o relatorio de mutacao.";
            } else {
                detail = "Falha Maven: " + errorLine;
            }
        } else if (pitConfigured) {
            detail = "Relatorio target/pit-reports/mutations.xml nao encontrado.";
        } else {
            detail = "Projeto sem plugin PIT configurado no pom.xml.";
        }

        return new ApiDiffSnapshot(
                "Antes - sem evidencias de mutacao",
                "Depois - status da execucao",
                List.of(new ApiDiffLine(1, "Aguardando relatorio de mutacao", "context")),
                List.of(
                        new ApiDiffLine(1, afterStatus, "context"),
                        new ApiDiffLine(2, detail, "added")));
    }

    private List<ApiProjectClass> scanClassesFromProject(ApiProject project) {
        String repositoryPath = normalize(project.repositoryPath());
        if (repositoryPath.isBlank()) {
            return List.of();
        }

        try {
            List<JavaClassCandidate> candidates = scanProjectUseCase.scan(Paths.get(repositoryPath));
            return candidates.stream()
                    .map(candidate -> {
                        ClassCost cost = classifyCost(candidate);
                        return new ApiProjectClass(
                                normalize(candidate.fullyQualifiedName()).isBlank()
                                        ? candidate.className()
                                        : candidate.fullyQualifiedName(),
                                normalize(candidate.packageName()),
                                cost.label,
                                cost.tone,
                                cost.estimatedMutants,
                                false);
                    })
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private ClassCost classifyCost(JavaClassCandidate candidate) {
        String name = candidate.className().toLowerCase(Locale.ROOT);
        String pkg  = candidate.packageName() == null ? "" : candidate.packageName().toLowerCase(Locale.ROOT);

        if (name.contains("filter") || name.contains("jwt")
                || (name.contains("security") && !name.endsWith("service"))
                || name.endsWith("config") || pkg.endsWith(".config")
                || (name.contains("exception") && name.contains("handler"))
                || name.contains("advice")) {
            return ClassCost.HIGH;
        }
        if (name.endsWith("controller")) return ClassCost.MEDIUM;
        if (name.endsWith("service") || name.endsWith("repository")) return ClassCost.LOW;
        if (name.contains("exception") || name.contains("handler")) return ClassCost.HIGH;
        return ClassCost.MEDIUM;
    }

    private enum ClassCost {
        LOW   ("Custo baixo",  "emerald",  8),
        MEDIUM("Custo médio",  "amber",   15),
        HIGH  ("Custo alto",   "soft-blue", 25);

        final String label;
        final String tone;
        final int estimatedMutants;

        ClassCost(String label, String tone, int estimatedMutants) {
            this.label = label;
            this.tone = tone;
            this.estimatedMutants = estimatedMutants;
        }
    }

    private List<String> buildRunCommand(String mavenPath, List<String> selectedClasses, boolean pitConfigured) {
        List<String> command = new ArrayList<>();
        command.add(mavenPath);
        command.add("-B");
        command.add("-Dstyle.color=never");

        String classesFilter = selectedClasses.stream()
                .map(this::normalize)
                .filter(value -> !value.isBlank())
                .distinct()
                .collect(Collectors.joining(","));

        if (pitConfigured) {
            if (!classesFilter.isBlank()) {
                command.add("-DtargetClasses=" + classesFilter);
            }
            command.add("pitest:mutationCoverage");
            return command;
        }

        command.add("-DskipTests=false");
        command.add("test");
        return command;
    }

    private List<String> buildPitRunnerPreview(String mavenPath, List<String> selectedClasses) {
        List<String> preview = new ArrayList<>();
        preview.add("pit-runner");
        preview.add(pitRunnerClient.baseUrl() + "/run-pit");
        preview.add(mavenPath);

        String classesFilter = selectedClasses == null
                ? ""
                : selectedClasses.stream()
                        .map(this::normalize)
                        .filter(value -> !value.isBlank())
                        .distinct()
                        .collect(Collectors.joining(","));

        if (!classesFilter.isBlank()) {
            preview.add(classesFilter);
        }

        return List.copyOf(preview);
    }

    private List<String> buildTestRunnerPreview(String mavenPath) {
        return List.of("pit-runner", pitRunnerClient.baseUrl() + "/run-tests", mavenPath);
    }

    private CommandExecutionResult executePitWithRunner(Path repositoryRoot, String mavenPath, List<String> selectedClasses) {
        try {
            return pitRunnerClient.executePit(repositoryRoot, mavenPath, selectedClasses);
        } catch (Exception exception) {
            return new CommandExecutionResult(
                    buildPitRunnerPreview(mavenPath, selectedClasses),
                    1,
                    false,
                    0,
                    List.of("Runner local PIT indisponivel: " + normalize(exception.getMessage())));
        }
    }

    private CommandExecutionResult executeTestPreflight(Path repositoryRoot, String mavenPath) {
        try {
            return pitRunnerClient.executeTests(repositoryRoot, mavenPath);
        } catch (Exception exception) {
            return new CommandExecutionResult(
                    buildTestRunnerPreview(mavenPath),
                    1,
                    false,
                    0,
                    List.of("Suite base de testes falhou: runner local indisponivel - " + normalize(exception.getMessage())));
        }
    }

    private CommandExecutionResult executePitWithFallback(Path repositoryRoot, String mavenPath, List<String> selectedClasses) throws Exception {
        CommandExecutionResult result = executePitWithRunner(repositoryRoot, mavenPath, selectedClasses);
        if (result.exitCode() != 0 && isPitRunnerUnavailable(result)) {
            return commandExecutor.execute(repositoryRoot, buildRunCommand(mavenPath, selectedClasses, true));
        }
        return result;
    }

    private CommandExecutionResult executeTestPreflightWithFallback(Path repositoryRoot, String mavenPath) throws Exception {
        CommandExecutionResult result = executeTestPreflight(repositoryRoot, mavenPath);
        if (result.exitCode() != 0 && isPitRunnerUnavailable(result)) {
            return commandExecutor.execute(repositoryRoot, buildRunCommand(mavenPath, List.of(), false));
        }
        return result;
    }

    private boolean isPitRunnerUnavailable(CommandExecutionResult result) {
        return result.outputLines().stream()
                .anyMatch(line -> line.contains("runner local indisponivel")
                        || line.contains("Runner local PIT indisponivel"));
    }

    private WorkspaceState createEmptyState(ApiProject project) {
        return new WorkspaceState(
                project,
                List.of(),
                List.of(),
                List.of(),
                EMPTY_DIFF_SNAPSHOT);
    }

    private WorkspaceState restoreWorkspaceState(ApiProject project) {
        String repositoryPath = normalize(project.repositoryPath());
        if (repositoryPath.isBlank()) {
            return createEmptyState(project);
        }

        Path repositoryRoot = Paths.get(repositoryPath).toAbsolutePath().normalize();
        Optional<DashboardStateCache> cachedDashboardState = dashboardStateCacheRepository.load(repositoryRoot);
        if (cachedDashboardState.isPresent()) {
            return workspaceStateFromDashboardState(project, cachedDashboardState.get());
        }
        return createEmptyState(project);
    }

    private WorkspaceState workspaceStateFromSummary(ApiProject project, PitestSummaryCache summary) {
        ApiProject hydratedProject = new ApiProject(
                project.id(),
                project.name(),
                project.repositoryPath(),
                project.mavenPath(),
                summary.mutationScore(),
                "Resumo PIT carregado");

        int coverageRate = summary.totalMutants() > 0
                ? Math.round(((summary.totalMutants() - summary.noCoverageMutants()) * 100f) / summary.totalMutants()) : 0;
        int coveredMutants = summary.totalMutants() - summary.noCoverageMutants();
        int survivorOfCoveredRate = coveredMutants > 0
                ? Math.round((summary.survivingMutants() * 100f) / coveredMutants) : 0;

        List<ApiGaugeMetric> gaugeMetrics = List.of(
                new ApiGaugeMetric(
                        "mutation-score",
                        "Mutation Score",
                        "Percentual de mutantes eliminados pelo total gerado",
                        summary.mutationScore(),
                        summary.mutationScore(),
                        "emerald"),
                new ApiGaugeMetric(
                        "coverage-rate",
                        "Cobertura de mutacao",
                        "Mutantes cobertos por pelo menos um teste",
                        coverageRate,
                        coverageRate,
                        "soft-blue"),
                new ApiGaugeMetric(
                        "survivor-of-covered",
                        "Sobreviventes cobertos",
                        "Dos cobertos, percentual que os testes nao detectaram",
                        survivorOfCoveredRate,
                        survivorOfCoveredRate,
                        "amber"));

        List<ApiInsightFeedback> insights = List.of(
                new ApiInsightFeedback(
                        "Resumo PIT carregado",
                        "Total de mutantes: " + summary.totalMutants()
                                + " | Mortos: " + summary.killedMutants()
                                + " | Sobreviventes: " + summary.survivingMutants()
                                + " | Sem cobertura: " + summary.noCoverageMutants() + ".",
                        "Resumo salvo em .mutation-ai/pitest-summary.json",
                        "soft-blue"));

        PitestMetrics metrics = new PitestMetrics(
                summary.mutationScore(),
                summary.totalMutants(),
                summary.killedMutants(),
                summary.survivingMutants(),
                summary.noCoverageMutants(),
                summary.survivorRate(),
                summary.killedRate(),
                summary.reportFile());

        return new WorkspaceState(
                hydratedProject,
                List.of(),
                gaugeMetrics,
                insights,
                EMPTY_DIFF_SNAPSHOT);
    }

    private WorkspaceState workspaceStateFromDashboardState(ApiProject project, DashboardStateCache state) {
        int mutationScore = state.gaugeMetrics().stream()
                .filter(metric -> "mutation-score".equals(metric.id()))
                .mapToInt(ApiGaugeMetric::after)
                .findFirst()
                .orElse(project.lastMutationScore());

        ApiProject hydratedProject = new ApiProject(
                project.id(),
                project.name(),
                project.repositoryPath(),
                project.mavenPath(),
                mutationScore,
                "Snapshot do dashboard carregado");

        return new WorkspaceState(
                hydratedProject,
                List.of(),
                state.gaugeMetrics(),
                state.insights(),
                state.diffSnapshot());
    }

    private Long loadPersistedDurationMs(ApiProject project) {
        String repositoryPath = normalize(project.repositoryPath());
        if (repositoryPath.isBlank()) {
            return null;
        }

        Path repositoryRoot = Paths.get(repositoryPath).toAbsolutePath().normalize();
        Optional<DashboardStateCache> dashboardState = dashboardStateCacheRepository.load(repositoryRoot);
        if (dashboardState.isPresent()) {
            return dashboardState.get().durationMs();
        }
        return null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private record WorkspaceState(
            ApiProject project,
            List<ApiProjectClass> classOptions,
            List<ApiGaugeMetric> gaugeMetrics,
            List<ApiInsightFeedback> insights,
            ApiDiffSnapshot diffSnapshot) {
        private WorkspaceState {
            classOptions = List.copyOf(new ArrayList<>(classOptions));
            gaugeMetrics = List.copyOf(new ArrayList<>(gaugeMetrics));
            insights = List.copyOf(new ArrayList<>(insights));
        }
    }
}
