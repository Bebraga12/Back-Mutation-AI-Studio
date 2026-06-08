package com.mutation.mutation_ai_studio.application.usecase;

import com.mutation.mutation_ai_studio.application.port.out.GeneratedTestRepositoryPort;
import com.mutation.mutation_ai_studio.application.port.out.TestExecutorPort;
import com.mutation.mutation_ai_studio.application.port.out.TestWorkspacePort;
import com.mutation.mutation_ai_studio.domain.model.ClassTestPrompt;
import com.mutation.mutation_ai_studio.domain.model.ClassAnalysis;
import com.mutation.mutation_ai_studio.domain.model.GeneratedTestBatch;
import com.mutation.mutation_ai_studio.domain.model.GeneratedTestCandidate;
import com.mutation.mutation_ai_studio.domain.model.GeneratedTestExecutionResult;
import com.mutation.mutation_ai_studio.domain.model.MethodAnalysis;
import com.mutation.mutation_ai_studio.domain.model.TestExecutionFeedback;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ExecuteGeneratedTestBatchServiceTest {

    @Mock
    private TestWorkspacePort testWorkspacePort;

    @Mock
    private TestExecutorPort testExecutorPort;

    @Mock
    private RefineGeneratedTestService refineGeneratedTestService;

    @Mock
    private GeneratedTestRepositoryPort generatedTestRepository;

    @Mock
    private GeneratedTestStructuralValidator structuralValidator;

    @InjectMocks
    private ExecuteGeneratedTestBatchService subject;

    @Test
    void shouldRunCompileBeforeExecutingTests() {
        GeneratedTestBatch batch = new GeneratedTestBatch("project", java.time.Instant.now(), List.of(candidate("LivroServiceTest")));
        Path workspacePath = Path.of("/tmp/LivroServiceTest.java");

        when(structuralValidator.validate(any())).thenReturn(List.of());
        when(testWorkspacePort.writeCandidate(any(), any())).thenReturn(workspacePath);
        when(testExecutorPort.compile(any(), eq("LivroServiceTest")))
                .thenReturn(new TestExecutionFeedback(true, 0, List.of(), "compile ok"));
        when(testExecutorPort.execute(any(), eq("LivroServiceTest")))
                .thenReturn(new TestExecutionFeedback(true, 0, List.of(), "test ok"));

        List<GeneratedTestExecutionResult> results = subject.execute(Path.of("/tmp/project"), batch);

        assertNotNull(results);
        verify(testExecutorPort).compile(any(), eq("LivroServiceTest"));
        verify(testExecutorPort).execute(any(), eq("LivroServiceTest"));
        verify(testWorkspacePort, never()).cleanup(workspacePath);
    }

    @Test
    void shouldRefineWithoutWritingWorkspaceWhenStructuralValidationFails() {
        GeneratedTestCandidate initialCandidate = candidate("LivroServiceTest");
        GeneratedTestCandidate refinedCandidate = candidate("LivroServiceTest");
        GeneratedTestBatch batch = new GeneratedTestBatch("project", java.time.Instant.now(), List.of(initialCandidate));

        when(structuralValidator.validate(any()))
                .thenReturn(List.of("Falha estrutural: anotacoes proibidas encontradas: SpringBootTest."))
                .thenReturn(List.of());
        when(testWorkspacePort.writeCandidate(any(), eq(refinedCandidate))).thenReturn(Path.of("/tmp/LivroServiceTest.java"));
        when(refineGeneratedTestService.refine(eq(initialCandidate.prompt()), eq(initialCandidate), any()))
                .thenReturn(refinedCandidate);
        when(testExecutorPort.compile(any(), eq("LivroServiceTest")))
                .thenReturn(new TestExecutionFeedback(true, 0, List.of(), "compile ok"));
        when(testExecutorPort.execute(any(), eq("LivroServiceTest")))
                .thenReturn(new TestExecutionFeedback(true, 0, List.of(), "test ok"));

        subject.execute(Path.of("/tmp/project"), batch);

        verify(testWorkspacePort, times(1)).writeCandidate(any(), any());
        verify(refineGeneratedTestService).refine(eq(initialCandidate.prompt()), eq(initialCandidate), any());
    }

    private GeneratedTestCandidate candidate(String testClassName) {
        ClassTestPrompt prompt = new ClassTestPrompt(
                "LivroService",
                "com.example.LivroService",
                "com/example/LivroService.java",
                List.of("LivroRepository livroRepository"),
                new ClassAnalysis(
                        "LivroService",
                        "com.example",
                        "LivroService(LivroRepository livroRepository)",
                        List.of("LivroRepository livroRepository"),
                        List.of(),
                        List.of(new MethodAnalysis("findById", "Livro", List.of("Long id"), List.of(), "{ return null; }")),
                        List.of("com.example.LivroRepository", "com.example.Livro"),
                        false,
                        false,
                        List.of()),
                "public class LivroService {}",
                "prompt",
                null
        );

        return new GeneratedTestCandidate(
                prompt,
                "LivroService",
                "com.example.LivroService",
                testClassName,
                """
                package com.example;

                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.InjectMocks;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;

                @ExtendWith(MockitoExtension.class)
                public class LivroServiceTest {
                    @Mock
                    private LivroRepository livroRepository;

                    @InjectMocks
                    private LivroService subject;

                    @Test
                    void deveBuscarLivro() {
                        subject.findById(1L);
                    }
                }
                """,
                null
        );
    }
}
