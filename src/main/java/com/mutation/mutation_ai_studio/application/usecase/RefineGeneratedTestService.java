package com.mutation.mutation_ai_studio.application.usecase;

import com.mutation.mutation_ai_studio.application.port.out.AiTestGeneratorPort;
import com.mutation.mutation_ai_studio.domain.model.ClassTestPrompt;
import com.mutation.mutation_ai_studio.domain.model.GeneratedTestCandidate;
import com.mutation.mutation_ai_studio.domain.model.TestExecutionFeedback;
import org.springframework.stereotype.Service;

@Service
public class RefineGeneratedTestService {

    private final AiTestGeneratorPort aiTestGeneratorPort;

    public RefineGeneratedTestService(AiTestGeneratorPort aiTestGeneratorPort) {
        this.aiTestGeneratorPort = aiTestGeneratorPort;
    }

    public GeneratedTestCandidate refine(ClassTestPrompt prompt,
                                         GeneratedTestCandidate previousCandidate,
                                         TestExecutionFeedback feedback) {
        boolean hasCannotFindSymbol = feedback.errors().stream()
                .anyMatch(error -> error.toLowerCase().contains("cannot find symbol"));
        boolean hasWrongTarget = feedback.errors().stream()
                .anyMatch(error -> error.toLowerCase().contains("troca indevida da classe alvo")
                        || error.toLowerCase().contains("não referencia explicitamente a classe alvo")
                        || error.toLowerCase().contains("classe de teste gerada não corresponde")
                        || error.toLowerCase().contains("@autowired")
                        || error.toLowerCase().contains("@springboottest"));
        boolean hasMissingImports = feedback.errors().stream()
                .anyMatch(error -> error.toLowerCase().contains("sem import explícito"));
        boolean hasAssertionMismatch = feedback.errors().stream()
                .anyMatch(error -> error.toLowerCase().contains("expected:") || error.toLowerCase().contains("but was:"));
        boolean hasWantedButNotInvoked = feedback.errors().stream()
                .anyMatch(error -> error.toLowerCase().contains("wanted but not invoked"));
        boolean likelyArgumentMatcherIssue = (hasAssertionMismatch || hasWantedButNotInvoked)
                && !hasCannotFindSymbol && !hasWrongTarget;
        boolean hasNullPointerException = feedback.errors().stream()
                .anyMatch(error -> error.toLowerCase().contains("nullpointer")
                        || (error.toLowerCase().contains("nonnull") && error.toLowerCase().contains("null"))
                        || error.toLowerCase().contains("non-null but is null")
                        || error.toLowerCase().contains("marked non-null"));

        String refinementPrompt = prompt.prompt()
                + System.lineSeparator()
                + System.lineSeparator()
                + "O teste anterior falhou na execução real do Maven." + System.lineSeparator()
                + "Corrija o arquivo abaixo com base nos erros reais." + System.lineSeparator()
                + "Nome esperado da classe de teste: " + previousCandidate.testClassName() + System.lineSeparator()
                + "Erros reais:" + System.lineSeparator()
                + String.join(System.lineSeparator(), feedback.errors()) + System.lineSeparator()
                + System.lineSeparator()
                + buildSymbolGuidance(prompt, hasCannotFindSymbol, hasWrongTarget, hasMissingImports, likelyArgumentMatcherIssue, hasNullPointerException) + System.lineSeparator()
                + System.lineSeparator()
                + "Teste anterior:" + System.lineSeparator()
                + previousCandidate.sourceCode();

        ClassTestPrompt refinementRequest = new ClassTestPrompt(
                prompt.className(),
                prompt.fullyQualifiedName(),
                prompt.relativePath(),
                prompt.dependencies(),
                prompt.analysis(),
                prompt.sourceCode(),
                refinementPrompt,
                prompt.savedPath()
        );

        String refinedCode = GeneratedTestSourceNormalizer.normalize(
                aiTestGeneratorPort.generateTestCode(refinementRequest),
                refinementRequest
        );
        return new GeneratedTestCandidate(
                refinementRequest,
                previousCandidate.className(),
                previousCandidate.fullyQualifiedName(),
                previousCandidate.testClassName(),
                refinedCode,
                previousCandidate.savedPath()
        );
    }

    private String buildSymbolGuidance(ClassTestPrompt prompt, boolean hasCannotFindSymbol, boolean hasWrongTarget,
                                        boolean hasMissingImports, boolean likelyArgumentMatcherIssue,
                                        boolean hasNullPointerException) {
        if (hasWrongTarget) {
            return "Orientações obrigatórias para corrigir o alvo do teste:" + System.lineSeparator()
                    + "- gere teste exclusivamente para a classe alvo " + prompt.className() + System.lineSeparator()
                    + "- o nome da classe de teste deve ser exatamente " + prompt.className() + "Test" + System.lineSeparator()
                    + "- não troque service por controller, nem controller por service" + System.lineSeparator()
                    + "- não use @SpringBootTest" + System.lineSeparator()
                    + "- não use @Autowired" + System.lineSeparator()
                    + "- use teste unitário puro com Mockito e a classe alvo correta";
        }

        if (likelyArgumentMatcherIssue) {
            return "The test failed with assertion or invocation mismatches — this is almost certainly a Mockito argument matching problem." + System.lineSeparator()
                    + "MANDATORY FIXES:" + System.lineSeparator()
                    + "- Look at every method body shown in METHODS TO TEST above." + System.lineSeparator()
                    + "- If a method creates a NEW object internally (e.g. `Autor autor = new Autor(); autor.setId(id);`)" + System.lineSeparator()
                    + "  and passes it to a repository, you CANNOT match that instance in the test — there is no equals()." + System.lineSeparator()
                    + "- Replace: `when(repo.findByAutor(autor)).thenReturn(...)` and `verify(repo).findByAutor(autor)`" + System.lineSeparator()
                    + "  with:    `when(repo.findByAutor(any(Autor.class))).thenReturn(...)` and `verify(repo).findByAutor(any(Autor.class))`" + System.lineSeparator()
                    + "- Apply this pattern for EVERY collaborator call where the argument is constructed inside the method under test." + System.lineSeparator()
                    + "- Do NOT create a matching instance variable in the test to stub an internally-created object." + System.lineSeparator()
                    + "- When in doubt about whether equals() exists, prefer `any(Type.class)` over a specific instance.";
        }

        if (hasNullPointerException && !hasCannotFindSymbol) {
            return "The test threw a NullPointerException — test data is missing required non-null property values." + System.lineSeparator()
                    + "MANDATORY FIXES:" + System.lineSeparator()
                    + "- Look at the method body in METHODS TO TEST: wherever `param.getX()` is passed to a setter," + System.lineSeparator()
                    + "  the test MUST first call `param.setX(someValue)` to set a real non-null value." + System.lineSeparator()
                    + "- For String fields: use a descriptive literal like `param.setTitle(\"Test Title\")`." + System.lineSeparator()
                    + "- For int/long fields: use a valid number like `param.setYear(2023)`." + System.lineSeparator()
                    + "- Do NOT rely on default values (null/0) when the method will pass those values to a setter" + System.lineSeparator()
                    + "  annotated with @Nonnull or @NonNull — they enforce null-rejection at runtime." + System.lineSeparator()
                    + "- Set ALL fields that the method reads from a parameter object before calling the method.";
        }

        if (hasMissingImports) {
            return "Orientações obrigatórias para corrigir imports faltantes:" + System.lineSeparator()
                    + "- adicione import explícito para todo tipo usado no teste" + System.lineSeparator()
                    + "- use estes imports reais da classe alvo como base: " + prompt.analysis().importedTypes() + System.lineSeparator()
                    + "- se usar entidades ou repositories do projeto, importe-os explicitamente" + System.lineSeparator()
                    + "- não deixe tipos como AutorRepository, LivroRepository, Autor, Livro, Login, Usuario, Optional, AuthenticationManager ou JwtServiceGenerator sem import";
        }

        if (!hasCannotFindSymbol) {
            return "Ajuste o teste preservando nomes reais, imports corretos e compatibilidade com o código da classe alvo.";
        }

        return "Orientações obrigatórias para corrigir cannot find symbol:" + System.lineSeparator()
                + "- use somente classes e collaborators reais já presentes no código-fonte da classe alvo" + System.lineSeparator()
                + "- reutilize os imports reais da análise estrutural: " + prompt.analysis().importedTypes() + System.lineSeparator()
                + "- reutilize os fields de dependência reais: " + prompt.analysis().fieldDependencies() + System.lineSeparator()
                + "- reutilize o construtor principal identificado: " + prompt.analysis().constructorSignature() + System.lineSeparator()
                + "- não invente nomes como Repository, Service, Entity ou DTO se eles não existirem exatamente com esse nome" + System.lineSeparator()
                + "- se faltar import, importe o tipo real existente no projeto em vez de criar outro nome";
    }
}
