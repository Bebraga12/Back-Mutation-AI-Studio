package com.mutation.mutation_ai_studio.application.usecase;

import com.mutation.mutation_ai_studio.application.port.out.SelectionRepositoryPort;
import com.mutation.mutation_ai_studio.application.port.out.SourceCodeAnalyzerPort;
import com.mutation.mutation_ai_studio.domain.model.ClassAnalysis;
import com.mutation.mutation_ai_studio.domain.model.JavaClassCandidate;
import com.mutation.mutation_ai_studio.domain.model.MethodAnalysis;
import com.mutation.mutation_ai_studio.domain.model.SelectionSnapshot;
import com.mutation.mutation_ai_studio.domain.model.TestPromptBatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CreateTestPromptServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private SelectionRepositoryPort selectionRepositoryPort;

    @Mock
    private SourceCodeAnalyzerPort sourceCodeAnalyzerPort;

    @InjectMocks
    private CreateTestPromptService service;

    @Test
    void createBuildsPromptFromSelectionAndSanitizesSourceCode() throws Exception {
        JavaClassCandidate candidate = new JavaClassCandidate(
                "BookService",
                "com.example.books",
                "com.example.books.BookService",
                "com/example/books/BookService.java"
        );
        SelectionSnapshot selection = new SelectionSnapshot(
                tempDir.toString(),
                Instant.parse("2026-05-14T12:00:00Z"),
                1,
                List.of(candidate)
        );

        Path sourceFile = tempDir.resolve("src/main/java/com/example/books/BookService.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, """
                package com.example.books;

                /* bloco removido */
                import java.util.List;

                // linha removida
                public class BookService {
                    private final BookRepository bookRepository;

                    public BookService(BookRepository bookRepository) {
                        this.bookRepository = bookRepository;
                    }

                    public List<String> list() {
                        return List.of();
                    }
                }
                """);

        AtomicReference<String> capturedSource = new AtomicReference<>();
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

        when(selectionRepositoryPort.read(tempDir)).thenReturn(Optional.of(selection));
        when(sourceCodeAnalyzerPort.analyze(eq(tempDir), eq(candidate), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    capturedSource.set(invocation.getArgument(2, String.class));
                    return analysis;
                });

        TestPromptBatch batch = service.create(tempDir);

        verify(selectionRepositoryPort).read(tempDir);
        verify(sourceCodeAnalyzerPort).analyze(eq(tempDir), eq(candidate), org.mockito.ArgumentMatchers.anyString());

        assertEquals(1, batch.totalSelected());
        assertEquals(1, batch.prompts().size());

        String sanitizedSource = capturedSource.get();
        assertNotNull(sanitizedSource);
        assertFalse(sanitizedSource.contains("/*"));
        assertFalse(sanitizedSource.contains("//"));
        assertTrue(sanitizedSource.contains("package com.example.books;"));
        assertTrue(sanitizedSource.contains("public class BookService"));

        String prompt = batch.prompts().getFirst().prompt();
        assertTrue(prompt.contains("BookServiceTest"));
        assertTrue(prompt.contains("com.example.books"));
        assertTrue(prompt.contains("@Mock"));
        assertTrue(prompt.contains("BookRepository bookRepository"));
        assertTrue(prompt.contains("@ExtendWith(MockitoExtension.class)"));
        assertTrue(prompt.contains("METHODS TO TEST"));
        assertTrue(prompt.contains("list()"));
    }
}
