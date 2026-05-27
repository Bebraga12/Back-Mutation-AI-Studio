package com.mutation.mutation_ai_studio.application.usecase;

import com.mutation.mutation_ai_studio.application.port.out.GeneratedTestRepositoryPort;
import com.mutation.mutation_ai_studio.application.port.out.TestExecutorPort;
import com.mutation.mutation_ai_studio.application.port.out.TestWorkspacePort;
import com.mutation.mutation_ai_studio.domain.model.ClassAnalysis;
import com.mutation.mutation_ai_studio.domain.model.ClassTestPrompt;
import com.mutation.mutation_ai_studio.domain.model.GeneratedTestBatch;
import com.mutation.mutation_ai_studio.domain.model.GeneratedTestCandidate;
import com.mutation.mutation_ai_studio.domain.model.GeneratedTestExecutionResult;
import com.mutation.mutation_ai_studio.domain.model.MethodAnalysis;
import com.mutation.mutation_ai_studio.domain.model.TestExecutionFeedback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class ExecuteGeneratedTestBatchServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private TestWorkspacePort testWorkspacePort;

    @Mock
    private TestExecutorPort testExecutorPort;

    @Mock
    private RefineGeneratedTestService refineGeneratedTestService;

    @Mock
    private GeneratedTestRepositoryPort generatedTestRepositoryPort;

    @InjectMocks
    private ExecuteGeneratedTestBatchService service;

    @Test
    void executeWritesCandidateRefinesOnFailureAndStopsWhenMavenPasses() {
        ClassAnalysis analysis = new ClassAnalysis(
                "BookService",
                "com.example.books",
                "BookService(BookRepository bookRepository)",
                List.of("BookRepository bookRepository"),
                List.of("BookRepository bookRepository"),
                List.of(new MethodAnalysis("list", "List<String>", List.of(), List.of(), "{ return List.of(); }")),
                List.of("java.util.List"),
                false,
                false
        );
        ClassTestPrompt prompt = new ClassTestPrompt(
                "BookService",
                "com.example.books.BookService",
                "com/example/books/BookService.java",
                List.of("BookRepository bookRepository"),
                analysis,
                "package com.example.books;\npublic class BookService {}",
                "prompt text",
                tempDir.resolve(".mutation-ai/generated/create-test-20260514-120000/BookServiceTest.java")
        );
        GeneratedTestCandidate initialCandidate = new GeneratedTestCandidate(
                prompt,
                "BookService",
                "com.example.books.BookService",
                "BookServiceTest",
                "package com.example.books;\npublic class BookServiceTest {}",
                prompt.savedPath()
        );
        GeneratedTestBatch batch = new GeneratedTestBatch(
                tempDir.toString(),
                Instant.parse("2026-05-14T12:00:00Z"),
                List.of(initialCandidate)
        );

        Path firstWorkspace = tempDir.resolve("workspace/attempt1/BookServiceTest.java");
        Path secondWorkspace = tempDir.resolve("workspace/attempt2/BookServiceTest.java");
        when(testWorkspacePort.writeCandidate(eq(tempDir), any())).thenReturn(firstWorkspace, secondWorkspace);
        when(testExecutorPort.execute(eq(tempDir), eq("BookServiceTest")))
                .thenReturn(new TestExecutionFeedback(false, 1, List.of("[ERROR] cannot find symbol"), "compilation failed"))
                .thenReturn(new TestExecutionFeedback(true, 0, List.of(), "tests passed"));

        GeneratedTestCandidate refinedCandidate = new GeneratedTestCandidate(
                prompt,
                "BookService",
                "com.example.books.BookService",
                "BookServiceTest",
                "package com.example.books;\npublic class BookServiceTest { /* refined */ }",
                prompt.savedPath()
        );
        when(refineGeneratedTestService.refine(eq(prompt), any(), any())).thenReturn(refinedCandidate);

        List<GeneratedTestExecutionResult> results = service.execute(tempDir, batch);

        assertEquals(1, results.size());
        GeneratedTestExecutionResult result = results.getFirst();
        assertTrue(result.feedback().passed());
        assertTrue(result.feedback().errors().isEmpty());
        assertEquals(refinedCandidate.sourceCode(), result.candidate().sourceCode());
        assertEquals(secondWorkspace, result.workspacePath());
        assertEquals(secondWorkspace, result.preservedPath());

        verify(testWorkspacePort, times(2)).writeCandidate(eq(tempDir), any());
        verify(testWorkspacePort).cleanup(firstWorkspace);
        verify(testWorkspacePort, never()).cleanup(secondWorkspace);
        verify(refineGeneratedTestService).refine(eq(prompt), any(), any());
        verify(generatedTestRepositoryPort, never()).saveFailed(any(), any(), any());
    }
}
