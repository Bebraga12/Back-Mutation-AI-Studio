package com.mutation.mutation_ai_studio.application.usecase;

import com.mutation.mutation_ai_studio.domain.model.ClassAnalysis;
import com.mutation.mutation_ai_studio.domain.model.ClassTestPrompt;
import com.mutation.mutation_ai_studio.domain.model.GeneratedTestCandidate;
import com.mutation.mutation_ai_studio.domain.model.MethodAnalysis;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedTestStructuralValidatorTest {

    private final GeneratedTestStructuralValidator subject = new GeneratedTestStructuralValidator();

    @Test
    void shouldRejectSpringBootTestAndWrongSubjectCall() {
        GeneratedTestCandidate candidate = candidate("""
                package com.example;

                import org.junit.jupiter.api.Test;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.mockito.InjectMocks;

                @SpringBootTest
                public class LivroServiceTest {
                    @InjectMocks
                    private LivroService subject;

                    @Test
                    void deveFalhar() {
                        subject.metodoPrivado();
                    }
                }
                """);

        List<String> errors = subject.validate(candidate);

        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(error -> error.contains("anotacoes proibidas")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("nao publicos ou inexistentes")));
    }

    @Test
    void shouldAcceptWellStructuredUnitTest() {
        GeneratedTestCandidate candidate = candidate("""
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
                """);

        List<String> errors = subject.validate(candidate);

        assertTrue(errors.isEmpty());
    }

    private GeneratedTestCandidate candidate(String sourceCode) {
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
                        List.of("metodoPrivado")),
                "public class LivroService {}",
                "prompt",
                null
        );

        return new GeneratedTestCandidate(
                prompt,
                "LivroService",
                "com.example.LivroService",
                "LivroServiceTest",
                sourceCode,
                null
        );
    }
}
