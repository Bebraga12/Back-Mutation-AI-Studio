package com.mutation.mutation_ai_studio.application.usecase;

import com.mutation.mutation_ai_studio.application.port.out.AiTestGeneratorPort;
import com.mutation.mutation_ai_studio.application.port.out.GeneratedTestRepositoryPort;
import com.mutation.mutation_ai_studio.domain.model.ClassAnalysis;
import com.mutation.mutation_ai_studio.domain.model.ClassTestPrompt;
import com.mutation.mutation_ai_studio.domain.model.GeneratedTestBatch;
import com.mutation.mutation_ai_studio.domain.model.MethodAnalysis;
import com.mutation.mutation_ai_studio.domain.model.TestPromptBatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenerateTestFromPromptServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private AiTestGeneratorPort aiTestGeneratorPort;

    @Mock
    private GeneratedTestRepositoryPort generatedTestRepositoryPort;

    @InjectMocks
    private GenerateTestFromPromptService service;

    @Test
    void generateSanitizesGeneratedCodeAndPersistsCandidate() {
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
                null
        );
        TestPromptBatch batch = new TestPromptBatch(
                tempDir.toString(),
                Instant.parse("2026-05-14T12:00:00Z"),
                1,
                List.of(prompt)
        );

        Path savedPath = tempDir.resolve(".mutation-ai/generated/create-test-20260514-120000/BookServiceTest.java");
        when(aiTestGeneratorPort.generateTestCode(prompt)).thenReturn("""
                Aqui está o teste:
                ```java
                package com.example.books;

                public class BookServiceTest {
                }
                ```
                """);
        when(generatedTestRepositoryPort.save(any(), any(), any())).thenReturn(savedPath);

        GeneratedTestBatch generatedBatch = service.generate(tempDir, batch);

        ArgumentCaptor<com.mutation.mutation_ai_studio.domain.model.GeneratedTestCandidate> captor =
                ArgumentCaptor.forClass(com.mutation.mutation_ai_studio.domain.model.GeneratedTestCandidate.class);
        verify(generatedTestRepositoryPort).save(any(), captor.capture(), any());

        String savedCode = captor.getValue().sourceCode();
        assertFalse(savedCode.contains("```"));
        assertTrue(savedCode.startsWith("package com.example.books;"));
        assertTrue(savedCode.contains("public class BookServiceTest"));
        assertEquals(savedPath, generatedBatch.candidates().getFirst().savedPath());
        assertEquals(savedCode, generatedBatch.candidates().getFirst().sourceCode());
    }
}
