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

    public ExecuteGeneratedTestBatchService(TestWorkspacePort testWorkspacePort,
                                            TestExecutorPort testExecutorPort,
                                            RefineGeneratedTestService refineGeneratedTestService,
                                            GeneratedTestRepositoryPort generatedTestRepository) {
        this.testWorkspacePort = testWorkspacePort;
        this.testExecutorPort = testExecutorPort;
        this.refineGeneratedTestService = refineGeneratedTestService;
        this.generatedTestRepository = generatedTestRepository;
    }

    @Override
    public List<GeneratedTestExecutionResult> execute(Path projectRoot, GeneratedTestBatch batch) {
        List<GeneratedTestExecutionResult> results = new ArrayList<>();
        for (GeneratedTestCandidate initialCandidate : batch.candidates()) {
            GeneratedTestCandidate candidate = initialCandidate;
            TestExecutionFeedback feedback = null;
            Path lastWorkspacePath = null;

            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                Path workspacePath = testWorkspacePort.writeCandidate(projectRoot, candidate);
                lastWorkspacePath = workspacePath;

                try {
                    feedback = testExecutorPort.execute(projectRoot, candidate.testClassName());
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

            Path preservedPath = feedback != null && feedback.passed()
                    ? lastWorkspacePath
                    : generatedTestRepository.saveFailed(projectRoot, candidate, batch.createdAt());

            results.add(new GeneratedTestExecutionResult(candidate, lastWorkspacePath, preservedPath, feedback));
        }
        return results;
    }
}
