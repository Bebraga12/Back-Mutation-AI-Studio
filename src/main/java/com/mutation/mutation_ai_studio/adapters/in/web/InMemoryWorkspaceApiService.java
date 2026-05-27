package com.mutation.mutation_ai_studio.adapters.in.web;

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
import com.mutation.mutation_ai_studio.adapters.in.web.dto.StartMutationRunRequest;
import com.mutation.mutation_ai_studio.application.port.in.ScanProjectUseCase;
import com.mutation.mutation_ai_studio.application.port.in.CreateTestPromptUseCase;
import com.mutation.mutation_ai_studio.application.port.in.GenerateTestFromPromptUseCase;
import com.mutation.mutation_ai_studio.application.port.in.ValidateGeneratedTestsUseCase;
import com.mutation.mutation_ai_studio.application.port.in.RefineGeneratedTestsUseCase;
import com.mutation.mutation_ai_studio.application.port.out.SelectionRepositoryPort;
import com.mutation.mutation_ai_studio.domain.model.JavaClassCandidate;
import com.mutation.mutation_ai_studio.domain.model.ClassTestPrompt;
import com.mutation.mutation_ai_studio.domain.model.TestPromptBatch;
import com.mutation.mutation_ai_studio.domain.model.GeneratedTestBatch;
import com.mutation.mutation_ai_studio.domain.model.ValidatedTestBatch;
import com.mutation.mutation_ai_studio.domain.model.RefinementBatch;
import com.mutation.mutation_ai_studio.domain.model.RefinementResult;
import com.mutation.mutation_ai_studio.domain.model.ValidatedTestResult;
import com.mutation.mutation_ai_studio.domain.model.SelectionSnapshot;
import org.springframework.stereotype.Service;

import java.time.Instant;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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

    private final ScanProjectUseCase scanProjectUseCase;
    private final CreateTestPromptUseCase createTestPromptUseCase;
    private final GenerateTestFromPromptUseCase generateTestFromPromptUseCase;
    private final ValidateGeneratedTestsUseCase validateGeneratedTestsUseCase;
    private final RefineGeneratedTestsUseCase refineGeneratedTestsUseCase;
    private final SelectionRepositoryPort selectionRepositoryPort;

    private final Map<String, WorkspaceState> workspaceByProjectId = new ConcurrentHashMap<>();
    private final Map<String, MutationRunState> mutationRunsByRunId = new ConcurrentHashMap<>();
    private final ExecutorService mutationRunExecutor = Executors.newCachedThreadPool();
    private final MavenResolver mavenResolver = new MavenResolver();
    private final PitestReportLoader pitestReportLoader = new PitestReportLoader();
    private final CommandExecutor commandExecutor = new CommandExecutor(RUN_TIMEOUT_MINUTES, MAX_OUTPUT_LINES);

    public InMemoryWorkspaceApiService(
            ScanProjectUseCase scanProjectUseCase,
            CreateTestPromptUseCase createTestPromptUseCase,
            GenerateTestFromPromptUseCase generateTestFromPromptUseCase,
            ValidateGeneratedTestsUseCase validateGeneratedTestsUseCase,
            RefineGeneratedTestsUseCase refineGeneratedTestsUseCase,
            SelectionRepositoryPort selectionRepositoryPort) {
        this.scanProjectUseCase = scanProjectUseCase;
        this.createTestPromptUseCase = createTestPromptUseCase;
        this.generateTestFromPromptUseCase = generateTestFromPromptUseCase;
        this.validateGeneratedTestsUseCase = validateGeneratedTestsUseCase;
        this.refineGeneratedTestsUseCase = refineGeneratedTestsUseCase;
        this.selectionRepositoryPort = selectionRepositoryPort;
        seed();
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
        mutationRunExecutor.submit(() -> executeMutationRun(runId));

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

    public Optional<ApiMavenDetectionResult> detectMaven(String projectId) {
        return Optional.ofNullable(workspaceByProjectId.get(projectId))
                .map(state -> mavenResolver.resolve(state.project().mavenPath()));
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

        // 1. Gera e valida os testes usando a IA (Ollama) em segundo plano antes de rodar o PIT
        runState.markRunning("Gerando testes com IA local (Ollama)...", List.of());
        try {
            // Cria a seleção do projeto no arquivo selection.json dinamicamente
            List<JavaClassCandidate> candidates = new ArrayList<>();
            for (String fqcn : runState.classes()) {
                String className = fqcn.substring(fqcn.lastIndexOf('.') + 1);
                String relPath = fqcn.replace('.', '/') + ".java";
                candidates.add(new JavaClassCandidate(className, fqcn, relPath));
            }
            SelectionSnapshot selection = new SelectionSnapshot(repositoryRoot.toString(), Instant.now(), candidates.size(), candidates);
            selectionRepositoryPort.save(repositoryRoot, selection);

            // Executa o pipeline de geração de testes com IA do Hexagonal
            TestPromptBatch batch = createTestPromptUseCase.create(repositoryRoot);
            GeneratedTestBatch generatedBatch = generateTestFromPromptUseCase.generate(repositoryRoot, batch);
            ValidatedTestBatch validatedBatch = validateGeneratedTestsUseCase.validate(repositoryRoot, batch, generatedBatch);
            RefinementBatch refinementBatch = refineGeneratedTestsUseCase.refine(repositoryRoot, batch, validatedBatch);

            // Copia os testes aprovados para a pasta real src/test/java do projeto alvo
            int approvedCount = 0;
            for (RefinementResult result : refinementBatch.results()) {
                ValidatedTestResult finalResult = result.finalResult();
                if (finalResult.approved()) {
                    String relPath = finalResult.fullyQualifiedName().replace('.', '/') + "Test.java";
                    Path testDestPath = repositoryRoot.resolve("src/test/java").resolve(relPath).normalize();
                    Files.createDirectories(testDestPath.getParent());
                    Files.writeString(testDestPath, finalResult.sanitizedCode());
                    approvedCount++;
                }
            }

            if (approvedCount == 0) {
                runState.markFailed("A IA gerou testes, mas nenhum deles passou nas validações de compilação/semântica.", null);
                return;
            }
        } catch (Exception e) {
            runState.markFailed("Falha na geração de testes com a IA: " + e.getMessage(), null);
            return;
        }

        boolean pitConfigured = pitestReportLoader.hasPitestPlugin(repositoryRoot);
        List<String> command = buildRunCommand(runState.mavenPath(), runState.classes(), pitConfigured);
        runState.markRunning("Executando testes e PIT Mutation Testing no repositório...", command);

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
                    .map(candidate -> new ApiProjectClass(
                            normalize(candidate.fullyQualifiedName()).isBlank()
                                    ? candidate.className()
                                    : candidate.fullyQualifiedName(),
                            normalize(candidate.packageName()),
                            "Detectada",
                            "soft-blue",
                            0,
                            false))
                    .toList();
        } catch (Exception ignored) {
            return List.of();
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

    private void seed() {
        // Inicializa sem projetos mockados.
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
