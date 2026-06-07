package com.mutation.mutation_ai_studio.adapters.in.cli;

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
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class CreateTestCliAdapter implements ApplicationRunner {

    private static final String CREATE_COMMAND = "create";
    private static final String CREATE_ALIAS = "c";
    private static final String TEST_COMMAND = "test";
    private static final String TEST_ALIAS = "t";

    private static final String PICK_FLAG = "--pick";

    @Value("${spring.ai.ollama.chat.model:qwen2.5-coder:7b}")
    private String ollamaModel;

    private final CreateTestPromptUseCase createTestPromptUseCase;
    private final GenerateTestFromPromptUseCase generateTestFromPromptUseCase;
    private final ExecuteGeneratedTestBatchUseCase executeGeneratedTestBatchUseCase;
    private final TestPromptRepositoryPort testPromptRepository;
    private final ScanProjectUseCase scanProjectUseCase;
    private final SelectionRepositoryPort selectionRepositoryPort;

    public CreateTestCliAdapter(CreateTestPromptUseCase createTestPromptUseCase,
                                GenerateTestFromPromptUseCase generateTestFromPromptUseCase,
                                ExecuteGeneratedTestBatchUseCase executeGeneratedTestBatchUseCase,
                                TestPromptRepositoryPort testPromptRepository,
                                ScanProjectUseCase scanProjectUseCase,
                                SelectionRepositoryPort selectionRepositoryPort) {
        this.createTestPromptUseCase = createTestPromptUseCase;
        this.generateTestFromPromptUseCase = generateTestFromPromptUseCase;
        this.executeGeneratedTestBatchUseCase = executeGeneratedTestBatchUseCase;
        this.testPromptRepository = testPromptRepository;
        this.scanProjectUseCase = scanProjectUseCase;
        this.selectionRepositoryPort = selectionRepositoryPort;
    }

    @Override
    public void run(ApplicationArguments args) {
        String[] sourceArgs = args.getSourceArgs();
        if (!isCreateTestCommand(sourceArgs)) {
            return;
        }

        Path projectRoot = resolveProjectRoot(sourceArgs);

        if (hasPick(sourceArgs)) {
            List<JavaClassCandidate> allClasses = scanProjectUseCase.scan(projectRoot);
            new InteractiveClassPicker(ollamaModel).pick(allClasses, selected ->
                    selectionRepositoryPort.save(projectRoot, new SelectionSnapshot(
                            projectRoot.toString(), Instant.now(), selected.size(), selected)));
        }

        TestPromptBatch batch = createTestPromptUseCase.create(projectRoot);
        List<ClassTestPrompt> savedPrompts = savePrompts(projectRoot, batch);
        TestPromptBatch savedBatch = new TestPromptBatch(batch.projectRoot(), batch.createdAt(), batch.totalSelected(), savedPrompts);
        GeneratedTestBatch generatedBatch = generateTestFromPromptUseCase.generate(projectRoot, savedBatch);
        List<GeneratedTestExecutionResult> executionResults = executeGeneratedTestBatchUseCase.execute(projectRoot, generatedBatch);

        printSummary(projectRoot, savedBatch, savedPrompts, generatedBatch, executionResults);
    }

    private boolean isCreateTestCommand(String[] sourceArgs) {
        if (sourceArgs.length < 2) {
            return false;
        }

        return isCreateToken(sourceArgs[0]) && isTestToken(sourceArgs[1]);
    }

    private boolean hasPick(String[] sourceArgs) {
        for (String arg : sourceArgs) {
            if (PICK_FLAG.equalsIgnoreCase(arg)) {
                return true;
            }
        }
        return false;
    }

    private boolean isCreateToken(String value) {
        return CREATE_COMMAND.equals(value) || CREATE_ALIAS.equals(value);
    }

    private boolean isTestToken(String value) {
        return TEST_COMMAND.equals(value) || TEST_ALIAS.equals(value);
    }

    private Path resolveProjectRoot(String[] sourceArgs) {
        List<String> positionals = extractPositionalArgs(sourceArgs);
        if (positionals.isEmpty()) {
            return Paths.get("").toAbsolutePath().normalize();
        }

        String candidate = positionals.getFirst();
        if (".".equals(candidate)) {
            return Paths.get("").toAbsolutePath().normalize();
        }

        Path maybePath = Paths.get(candidate).toAbsolutePath().normalize();
        if (Files.exists(maybePath) && Files.isDirectory(maybePath)) {
            return maybePath;
        }

        return Paths.get("").toAbsolutePath().normalize();
    }

    private List<String> extractPositionalArgs(String[] sourceArgs) {
        List<String> positional = new ArrayList<>();
        for (int i = 2; i < sourceArgs.length; i++) {
            String current = sourceArgs[i];
            if (current.startsWith("--")) {
                continue;
            }
            positional.add(current);
        }
        return positional;
    }

    private List<ClassTestPrompt> savePrompts(Path projectRoot, TestPromptBatch batch) {
        List<ClassTestPrompt> savedPrompts = new ArrayList<>();
        for (ClassTestPrompt prompt : batch.prompts()) {
            Path savedPath = testPromptRepository.save(projectRoot, prompt, batch.createdAt());
            savedPrompts.add(new ClassTestPrompt(
                    prompt.className(),
                    prompt.fullyQualifiedName(),
                    prompt.relativePath(),
                    prompt.dependencies(),
                    prompt.analysis(),
                    prompt.sourceCode(),
                    prompt.prompt(),
                    savedPath,
                    null
            ));
        }
        return savedPrompts;
    }

    private void printSummary(Path projectRoot,
                              TestPromptBatch batch,
                              List<ClassTestPrompt> savedPrompts,
                              GeneratedTestBatch generatedBatch,
                              List<GeneratedTestExecutionResult> executionResults) {
        long approved = executionResults.stream().filter(r -> r.feedback().passed()).count();
        long failed = executionResults.size() - approved;

        int nameWidth = executionResults.stream()
                .mapToInt(r -> r.candidate().className().length())
                .max().orElse(20);

        System.out.println();
        System.out.printf("Projeto: %s%s%s%n", Ansi.BOLD, projectRoot.getFileName(), Ansi.RESET);
        System.out.println();

        executionResults.forEach(r -> printExecutionResult(r, projectRoot, nameWidth));

        System.out.println("  " + "─".repeat(nameWidth + 20));
        System.out.printf("  %d gerado(s)   ", executionResults.size());
        System.out.printf("%s%d aprovado(s)%s   ", Ansi.GREEN, approved, Ansi.RESET);
        if (failed > 0) {
            System.out.printf("%s%d falhou%s", Ansi.RED, failed, Ansi.RESET);
        }
        System.out.println();
        System.out.println();
    }

    private void printExecutionResult(GeneratedTestExecutionResult result, Path projectRoot, int nameWidth) {
        if (result.feedback().passed()) {
            String savedPath = result.preservedPath() != null
                    ? relativize(projectRoot, result.preservedPath())
                    : "";
            System.out.printf("  %s✓%s  %-" + nameWidth + "s  %s%s%s%n",
                    Ansi.GREEN, Ansi.RESET,
                    result.candidate().className(),
                    Ansi.GRAY, savedPath, Ansi.RESET);
        } else {
            System.out.printf("  %s✗%s  %s%s%s%n",
                    Ansi.RED, Ansi.RESET,
                    Ansi.BOLD, result.candidate().className(), Ansi.RESET);
            result.feedback().errors().stream()
                    .limit(3)
                    .forEach(e -> System.out.printf("       %s%s%s%n", Ansi.GRAY, e, Ansi.RESET));
            if (result.preservedPath() != null) {
                System.out.printf("       %s→ %s%s%n",
                        Ansi.GRAY, relativize(projectRoot, result.preservedPath()), Ansi.RESET);
            }
        }
    }

    private String relativize(Path projectRoot, Path absolute) {
        try {
            return projectRoot.relativize(absolute).toString();
        } catch (IllegalArgumentException e) {
            return absolute.toString();
        }
    }
}
