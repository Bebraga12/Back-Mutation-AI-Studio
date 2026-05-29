package com.mutation.mutation_ai_studio.adapters.in.web;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
                                       TestPromptRepositoryPort testPromptRepositoryPort) {
        this.scanProjectUseCase = scanProjectUseCase;
        this.createTestPromptUseCase = createTestPromptUseCase;
        this.generateTestFromPromptUseCase = generateTestFromPromptUseCase;
        this.executeGeneratedTestBatchUseCase = executeGeneratedTestBatchUseCase;
        this.selectionRepositoryPort = selectionRepositoryPort;
        this.testPromptRepositoryPort = testPromptRepositoryPort;
    }

    public List<ApiProject> listProjects() {
        return workspaceByProjectId.values().stream()
                .map(WorkspaceState::project)
                .sorted(Comparator.comparing(ApiProject::name))
                .toList();
    }

    public Optional<ApiProject> findProject(String projectId) {
        return Optional.ofNullable(workspaceByProjectId.get(projectId)).map(WorkspaceState::project);
    }

    public synchronized ApiProject createProject(CreateProjectRequest request) {
        String projectName = normalize(request.name());
        String projectId = buildUniqueProjectId(projectName);

        ApiProject project = new ApiProject(
                projectId,
                projectName,
                normalize(request.repositoryPath()),
                normalize(request.mavenPath()),
                0,
                "Projeto novo");

        workspaceByProjectId.put(projectId, createEmptyState(project));
        return project;
    }

    public synchronized boolean deleteProject(String projectId) {
        return workspaceByProjectId.remove(projectId) != null;
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
            runState.markCompleted(summary, results);

        } catch (Exception ex) {
            runState.markFailed("Falha na geracao: " + normalize(ex.getMessage()));
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
                .map(state -> mavenResolver.resolve(state.project().mavenPath(), state.project().repositoryPath()));
    }

    public Optional<ApiDashboardData> findDashboard(String projectId) {
        return Optional.ofNullable(workspaceByProjectId.get(projectId))
                .map(state -> {
                    if (state.project().lastMutationScore() <= 0
                            && state.gaugeMetrics().isEmpty()
                            && state.insights().isEmpty()) {
                        return new ApiDashboardData(List.of(), List.of(), EMPTY_DIFF_SNAPSHOT);
                    }

                    return new ApiDashboardData(
                            state.gaugeMetrics(),
                            state.insights(),
                            state.diffSnapshot());
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
        List<String> command = buildRunCommand(runState.mavenPath(), runState.classes(), pitConfigured);
        runState.markRunning("Executando Maven no repositorio selecionado.", command);

        CommandExecutionResult execution;
        try {
            execution = commandExecutor.execute(repositoryRoot, command);
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
            updateWorkspaceAfterRun(workspaceState.project().id(), workspaceState, execution, Optional.empty(), false);
            return;
        }

        if (execution.exitCode() != 0) {
            String lastLine = commandExecutor.extractLastNonBlankLine(execution.outputLines());
            String message = lastLine.isBlank()
                    ? "Execucao Maven finalizou com erro (exit code " + execution.exitCode() + ")."
                    : "Execucao Maven falhou: " + lastLine;
            runState.markFailed(message, execution);
            updateWorkspaceAfterRun(workspaceState.project().id(), workspaceState, execution, Optional.empty(), false);
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

            int previousSurvivorRate = resolvePreviousGaugeAfter(
                    currentState.gaugeMetrics(),
                    "survivor-pressure",
                    100 - previousScore);
            int currentSurvivorRate = metrics.survivorRate();
            int previousKilledRate = resolvePreviousGaugeAfter(
                    currentState.gaugeMetrics(),
                    "killed-pressure",
                    100 - previousSurvivorRate);
            int currentKilledRate = metrics.killedRate();

            nextGaugeMetrics = List.of(
                    new ApiGaugeMetric(
                            "mutation-score",
                            "Mutation Score",
                            "Indice real extraido do relatorio PIT",
                            previousScore,
                            nextScore,
                            "emerald"),
                    new ApiGaugeMetric(
                            "survivor-pressure",
                            "Mutantes sobreviventes",
                            "Percentual de mutantes ainda vivos",
                            previousSurvivorRate,
                            currentSurvivorRate,
                            "amber"),
                    new ApiGaugeMetric(
                            "killed-pressure",
                            "Mutantes eliminados",
                            "Percentual de mutantes mortos",
                            previousKilledRate,
                            currentKilledRate,
                            "soft-blue"));

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

            nextDiffSnapshot = buildPitDiffSnapshot(metrics);
        } else {
            boolean executionSucceeded = execution.exitCode() == 0 && !execution.timedOut();
            String detail;
            if (!executionSucceeded) {
                detail = "A execucao de testes falhou antes da geracao de metricas de mutacao.";
            } else if (pitConfigured) {
                detail = "A execucao terminou, mas nenhum mutations.xml foi encontrado em target/pit-reports.";
            } else {
                detail = "Os testes foram executados com sucesso, mas o projeto ainda nao possui plugin PIT configurado.";
            }

            nextInsights = List.of(
                    new ApiInsightFeedback(
                            executionSucceeded
                                    ? "Execucao concluida sem metricas de mutacao"
                                    : "Execucao com falha",
                            detail,
                            "Configure o plugin PIT no pom.xml para preencher Mutation Score, metricas e diff automaticamente.",
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
    }

    private int resolvePreviousGaugeAfter(List<ApiGaugeMetric> gauges, String id, int fallback) {
        return gauges.stream()
                .filter(gauge -> id.equals(gauge.id()))
                .map(ApiGaugeMetric::after)
                .findFirst()
                .orElse(fallback);
    }

    private ApiDiffSnapshot buildPitDiffSnapshot(PitestMetrics metrics) {
        List<ApiDiffLine> beforeLines = List.of(
                new ApiDiffLine(1, "Mutation score anterior: sem dados consolidados", "context"),
                new ApiDiffLine(2, "Mutantes sem cobertura: n/d", "removed"),
                new ApiDiffLine(3, "Mutantes sobreviventes: n/d", "removed"));

        List<ApiDiffLine> afterLines = List.of(
                new ApiDiffLine(1, "Mutation score atual: " + metrics.mutationScore() + "%", "context"),
                new ApiDiffLine(2, "Mutantes sem cobertura: " + metrics.noCoverage(),
                        metrics.noCoverage() > 0 ? "added" : "context"),
                new ApiDiffLine(3, "Mutantes sobreviventes: " + metrics.survivorCount(),
                        metrics.survivorCount() > 0 ? "added" : "context"),
                new ApiDiffLine(4, "Mutantes eliminados: " + metrics.killed(), "added"));

        return new ApiDiffSnapshot(
                "Antes - baseline da rodada",
                "Depois - resultado PIT",
                beforeLines,
                afterLines);
    }

    private ApiDiffSnapshot buildNoMetricsDiffSnapshot(CommandExecutionResult execution, boolean pitConfigured) {
        String afterStatus = execution.exitCode() == 0 && !execution.timedOut()
                ? "Execucao finalizada sem relatorio de mutacao"
                : "Execucao finalizada com falha";
        String detail = pitConfigured
                ? "Relatorio target/pit-reports/mutations.xml nao encontrado."
                : "Projeto sem plugin PIT configurado no pom.xml.";

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
            command.add("org.pitest:pitest-maven:mutationCoverage");
            return command;
        }

        command.add("-DskipTests=false");
        command.add("test");
        return command;
    }

    private WorkspaceState createEmptyState(ApiProject project) {
        return new WorkspaceState(
                project,
                List.of(),
                List.of(),
                List.of(),
                EMPTY_DIFF_SNAPSHOT);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String buildUniqueProjectId(String projectName) {
        String slug = projectName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");

        String base = slug.isBlank() ? "project" : slug;
        String current = base;
        int counter = 1;

        while (workspaceByProjectId.containsKey(current)) {
            current = base + "-" + counter;
            counter++;
        }

        return current;
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
