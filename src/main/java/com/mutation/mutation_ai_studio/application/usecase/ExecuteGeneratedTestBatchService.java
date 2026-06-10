package com.mutation.mutation_ai_studio.application.usecase;

import com.mutation.mutation_ai_studio.application.port.in.ExecuteGeneratedTestBatchUseCase;
import com.mutation.mutation_ai_studio.application.port.out.GeneratedTestRepositoryPort;
import com.mutation.mutation_ai_studio.application.port.out.TestExecutorPort;
import com.mutation.mutation_ai_studio.application.port.out.TestWorkspacePort;
import com.mutation.mutation_ai_studio.domain.model.ClassTestPrompt;
import com.mutation.mutation_ai_studio.domain.model.GeneratedTestBatch;
import com.mutation.mutation_ai_studio.domain.model.GeneratedTestCandidate;
import com.mutation.mutation_ai_studio.domain.model.GeneratedTestExecutionResult;
import com.mutation.mutation_ai_studio.domain.model.TestExecutionFeedback;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class ExecuteGeneratedTestBatchService implements ExecuteGeneratedTestBatchUseCase {

    private static final int MAX_ATTEMPTS = 4;

    private final TestWorkspacePort testWorkspacePort;
    private final TestExecutorPort testExecutorPort;
    private final RefineGeneratedTestService refineGeneratedTestService;
    private final GeneratedTestRepositoryPort generatedTestRepository;
    private final GeneratedTestStructuralValidator structuralValidator;

    public ExecuteGeneratedTestBatchService(TestWorkspacePort testWorkspacePort,
                                            TestExecutorPort testExecutorPort,
                                            RefineGeneratedTestService refineGeneratedTestService,
                                            GeneratedTestRepositoryPort generatedTestRepository,
                                            GeneratedTestStructuralValidator structuralValidator) {
        this.testWorkspacePort = testWorkspacePort;
        this.testExecutorPort = testExecutorPort;
        this.refineGeneratedTestService = refineGeneratedTestService;
        this.generatedTestRepository = generatedTestRepository;
        this.structuralValidator = structuralValidator;
    }

    @Override
    public List<GeneratedTestExecutionResult> execute(Path projectRoot, GeneratedTestBatch batch) {
        List<GeneratedTestExecutionResult> results = new ArrayList<>();
        for (GeneratedTestCandidate initialCandidate : batch.candidates()) {
            GeneratedTestCandidate candidate = initialCandidate;
            TestExecutionFeedback feedback = null;
            Path lastWorkspacePath = null;

            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                List<String> structuralErrors = structuralValidator.validate(candidate);
                if (!structuralErrors.isEmpty()) {
                    feedback = new TestExecutionFeedback(false, -1, structuralErrors, "");
                    if (attempt < MAX_ATTEMPTS) {
                        candidate = refineGeneratedTestService.refine(candidate.prompt(), candidate, feedback);
                        continue;
                    }
                    break;
                }

                Path workspacePath = testWorkspacePort.writeCandidate(projectRoot, candidate);
                lastWorkspacePath = workspacePath;

                try {
                    feedback = testExecutorPort.compile(projectRoot, candidate.testClassName());
                    if (feedback.passed()) {
                        feedback = testExecutorPort.execute(projectRoot, candidate.testClassName());
                    }
                } catch (RuntimeException e) {
                    testWorkspacePort.cleanup(workspacePath);
                    throw e;
                }

                if (feedback.passed()) {
                    break;
                }

                testWorkspacePort.cleanup(workspacePath);

                if (attempt < MAX_ATTEMPTS) {
                    ClassTestPrompt prompt = candidate.prompt();
                    candidate = refineGeneratedTestService.refine(prompt, candidate, feedback);
                }
            }

            if (feedback == null || !feedback.passed()) {
                FallbackOutcome fallback = tryFallback(projectRoot, candidate);
                if (fallback != null) {
                    candidate = fallback.candidate();
                    feedback = fallback.feedback();
                    lastWorkspacePath = fallback.workspacePath();
                }
            }

            Path preservedPath = feedback != null && feedback.passed()
                    ? lastWorkspacePath
                    : generatedTestRepository.saveFailed(projectRoot, candidate, batch.createdAt());

            results.add(new GeneratedTestExecutionResult(candidate, lastWorkspacePath, preservedPath, feedback));
        }
        return results;
    }

    /**
     * Last-resort fallback for when the AI (qwen2.5-coder:7b) exhausted every refinement
     * attempt without producing a passing test. Generates a minimal but always-compilable
     * smoke test (mocks every dependency, instantiates the target via @InjectMocks and asserts
     * it is not null). If it compiles and passes, the class still ends up with at least one
     * green test and a small mutation-coverage gain instead of zero.
     */
    private FallbackOutcome tryFallback(Path projectRoot, GeneratedTestCandidate candidate) {
        GeneratedTestCandidate fallbackCandidate = new GeneratedTestCandidate(
                candidate.prompt(),
                candidate.className(),
                candidate.fullyQualifiedName(),
                candidate.testClassName(),
                GeneratedTestFallbackFactory.generate(candidate.prompt()),
                null);

        Path workspacePath = testWorkspacePort.writeCandidate(projectRoot, fallbackCandidate);
        try {
            TestExecutionFeedback fallbackFeedback = testExecutorPort.compile(projectRoot, fallbackCandidate.testClassName());
            if (fallbackFeedback.passed()) {
                fallbackFeedback = testExecutorPort.execute(projectRoot, fallbackCandidate.testClassName());
            }
            if (fallbackFeedback.passed()) {
                return new FallbackOutcome(fallbackCandidate, fallbackFeedback, workspacePath);
            }
            testWorkspacePort.cleanup(workspacePath);
            return null;
        } catch (RuntimeException e) {
            testWorkspacePort.cleanup(workspacePath);
            return null;
        }
    }

    private record FallbackOutcome(GeneratedTestCandidate candidate, TestExecutionFeedback feedback, Path workspacePath) {
    }
}
