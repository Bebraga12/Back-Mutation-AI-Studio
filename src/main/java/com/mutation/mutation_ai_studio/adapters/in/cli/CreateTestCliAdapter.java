package com.mutation.mutation_ai_studio.adapters.in.cli;

import com.mutation.mutation_ai_studio.application.port.in.CreateTestPromptUseCase;
import com.mutation.mutation_ai_studio.application.port.in.ExecuteGeneratedTestBatchUseCase;
import com.mutation.mutation_ai_studio.application.port.in.GenerateTestFromPromptUseCase;
import com.mutation.mutation_ai_studio.application.port.out.TestPromptRepositoryPort;
import com.mutation.mutation_ai_studio.domain.model.ClassTestPrompt;
import com.mutation.mutation_ai_studio.domain.model.GeneratedTestBatch;
import com.mutation.mutation_ai_studio.domain.model.GeneratedTestExecutionResult;
import com.mutation.mutation_ai_studio.domain.model.TestPromptBatch;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Component
public class CreateTestCliAdapter implements ApplicationRunner {

    private static final String CREATE_COMMAND = "create";
    private static final String CREATE_ALIAS = "c";
    private static final String TEST_COMMAND = "test";
    private static final String TEST_ALIAS = "t";

    private final CreateTestPromptUseCase createTestPromptUseCase;
    private final GenerateTestFromPromptUseCase generateTestFromPromptUseCase;
    private final ExecuteGeneratedTestBatchUseCase executeGeneratedTestBatchUseCase;
    private final TestPromptRepositoryPort testPromptRepository;

    public CreateTestCliAdapter(CreateTestPromptUseCase createTestPromptUseCase,
                                GenerateTestFromPromptUseCase generateTestFromPromptUseCase,
                                ExecuteGeneratedTestBatchUseCase executeGeneratedTestBatchUseCase,
                                TestPromptRepositoryPort testPromptRepository) {
        this.createTestPromptUseCase = createTestPromptUseCase;
        this.generateTestFromPromptUseCase = generateTestFromPromptUseCase;
        this.executeGeneratedTestBatchUseCase = executeGeneratedTestBatchUseCase;
        this.testPromptRepository = testPromptRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        String[] sourceArgs = args.getSourceArgs();
        if (!isCreateTestCommand(sourceArgs)) {
            return;
        }

        Path projectRoot = resolveProjectRoot(sourceArgs);
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
                    savedPath
            ));
        }
        return savedPrompts;
    }

    private void printSummary(Path projectRoot,
                              TestPromptBatch batch,
                              List<ClassTestPrompt> savedPrompts,
                              GeneratedTestBatch generatedBatch,
                              List<GeneratedTestExecutionResult> executionResults) {
        System.out.printf("Projeto: %s%n", projectRoot);
        System.out.printf("Classes selecionadas: %d%n", batch.totalSelected());
        System.out.printf("Prompts gerados: %d%n", savedPrompts.size());
        System.out.printf("Testes candidatos gerados: %d%n", generatedBatch.candidates().size());

        long approved = executionResults.stream().filter(result -> result.feedback().passed()).count();
        long rejected = executionResults.size() - approved;
        System.out.printf("Execuções aprovadas no Maven: %d%n", approved);
        System.out.printf("Execuções com falha no Maven: %d%n", rejected);

        Path batchDirectory = savedPrompts.isEmpty()
                ? projectRoot.resolve(".mutation-ai/prompts")
                : savedPrompts.getFirst().savedPath().getParent();

        System.out.printf("Lote salvo em: %s%n", batchDirectory);
        System.out.println("Resultados por classe:");
        executionResults.forEach(this::printExecutionResult);
    }

    private void printExecutionResult(GeneratedTestExecutionResult result) {
        String status = result.feedback().passed() ? "APROVADO" : "FALHOU";
        System.out.printf(" - %s: %s%n", result.candidate().fullyQualifiedName(), status);
        if (result.feedback().passed()) {
            if (result.preservedPath() != null) {
                System.out.printf("   salvo em: %s%n", result.preservedPath());
            }
        } else {
            result.feedback().errors().stream()
                    .limit(5)
                    .forEach(error -> System.out.printf("   erro: %s%n", error));
            if (result.preservedPath() != null) {
                System.out.printf("   teste falho salvo em: %s%n", result.preservedPath());
            }
        }
    }
}
