package com.mutation.mutation_ai_studio.application.usecase;

import com.mutation.mutation_ai_studio.application.port.in.CreateTestPromptUseCase;
import com.mutation.mutation_ai_studio.application.port.out.SelectionRepositoryPort;
import com.mutation.mutation_ai_studio.application.port.out.SourceCodeAnalyzerPort;
import com.mutation.mutation_ai_studio.domain.model.ClassAnalysis;
import com.mutation.mutation_ai_studio.domain.model.ClassTestPrompt;
import com.mutation.mutation_ai_studio.domain.model.JavaClassCandidate;
import com.mutation.mutation_ai_studio.domain.model.MethodAnalysis;
import com.mutation.mutation_ai_studio.domain.model.SelectionSnapshot;
import com.mutation.mutation_ai_studio.domain.model.TestPromptBatch;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class CreateTestPromptService implements CreateTestPromptUseCase {

    private final SelectionRepositoryPort selectionRepositoryPort;
    private final SourceCodeAnalyzerPort sourceCodeAnalyzerPort;

    public CreateTestPromptService(SelectionRepositoryPort selectionRepositoryPort,
                                   SourceCodeAnalyzerPort sourceCodeAnalyzerPort) {
        this.selectionRepositoryPort = selectionRepositoryPort;
        this.sourceCodeAnalyzerPort = sourceCodeAnalyzerPort;
    }

    @Override
    public TestPromptBatch create(Path projectRoot) {
        SelectionSnapshot selection = selectionRepositoryPort.read(projectRoot)
                .orElseThrow(() -> new IllegalStateException("Nenhuma seleção encontrada para o projeto. Use `mutation-ai select .` antes de criar o prompt."));

        List<ClassTestPrompt> prompts = selection.classes().stream()
                .map(candidate -> toPrompt(projectRoot, candidate))
                .toList();

        return new TestPromptBatch(
                projectRoot.toString(),
                Instant.now(),
                selection.totalSelected(),
                prompts
        );
    }

    private ClassTestPrompt toPrompt(Path projectRoot, JavaClassCandidate candidate) {
        Path sourceFile = projectRoot.resolve("src/main/java").resolve(candidate.relativePath()).normalize();
        String rawSourceCode = readSourceCode(sourceFile);
        // JavaParser recebe o source original — strings com "://" não são confundidas com comentários
        ClassAnalysis analysis = sourceCodeAnalyzerPort.analyze(projectRoot, candidate, rawSourceCode);
        // O AI recebe a versão sanitizada (sem comentários) para economizar tokens
        String sourceCode = sanitizeSourceCode(rawSourceCode);
        List<String> dependencies = combineDependencies(analysis);
        String prompt = buildPrompt(candidate, sourceCode, dependencies, analysis);

        return new ClassTestPrompt(
                candidate.className(),
                candidate.fullyQualifiedName(),
                candidate.relativePath(),
                dependencies,
                analysis,
                sourceCode,
                prompt,
                null
        );
    }

    private String readSourceCode(Path sourceFile) {
        try {
            return Files.readString(sourceFile);
        } catch (IOException e) {
            throw new IllegalStateException("Erro ao ler código fonte da classe alvo: " + sourceFile, e);
        }
    }

    private List<String> combineDependencies(ClassAnalysis analysis) {
        LinkedHashSet<String> dependencies = new LinkedHashSet<>();
        dependencies.addAll(analysis.constructorDependencies());
        dependencies.addAll(analysis.fieldDependencies());
        return List.copyOf(dependencies);
    }

    private String sanitizeSourceCode(String sourceCode) {
        String normalized = sourceCode.replace("\r\n", "\n").trim();
        StringBuilder builder = new StringBuilder();
        boolean inBlockComment = false;

        for (String line : normalized.split("\n", -1)) {
            String current = line;
            if (inBlockComment) {
                int endIndex = current.indexOf("*/");
                if (endIndex < 0) {
                    continue;
                }
                current = current.substring(endIndex + 2);
                inBlockComment = false;
            }

            while (true) {
                int blockStart = current.indexOf("/*");
                int lineCommentStart = current.indexOf("//");

                if (blockStart >= 0 && (lineCommentStart < 0 || blockStart < lineCommentStart)) {
                    int blockEnd = current.indexOf("*/", blockStart + 2);
                    if (blockEnd >= 0) {
                        current = current.substring(0, blockStart) + current.substring(blockEnd + 2);
                        continue;
                    }

                    current = current.substring(0, blockStart);
                    inBlockComment = true;
                } else if (lineCommentStart >= 0) {
                    current = current.substring(0, lineCommentStart);
                }
                break;
            }

            String trimmed = current.stripTrailing();
            if (trimmed.isBlank()) {
                if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n') {
                    builder.append('\n');
                }
                continue;
            }

            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(trimmed);
        }

        return builder.toString().trim();
    }

    private String buildPrompt(JavaClassCandidate candidate, String sourceCode, List<String> dependencies, ClassAnalysis analysis) {
        String nl = System.lineSeparator();
        StringBuilder sb = new StringBuilder();

        sb.append("Generate a JUnit 5 + Mockito unit test file for the Java class below.").append(nl);
        sb.append("Output ONLY raw Java code. No markdown, no explanation, no code fences.").append(nl);
        sb.append(nl);

        sb.append("TEST CLASS: ").append(candidate.className()).append("Test").append(nl);
        sb.append("PACKAGE: ").append(analysis.packageName()).append(nl);
        sb.append(nl);

        if (!dependencies.isEmpty()) {
            boolean fieldInjection = analysis.constructorDependencies().isEmpty() && !analysis.fieldDependencies().isEmpty();
            sb.append("REQUIRED CLASS SKELETON — copy this EXACTLY, then fill in the @Test methods:").append(nl);
            sb.append("@ExtendWith(MockitoExtension.class)").append(nl);
            sb.append("public class ").append(candidate.className()).append("Test {").append(nl);
            sb.append(nl);
            for (String dep : dependencies) {
                sb.append("    @Mock").append(nl);
                sb.append("    private ").append(dep).append(";").append(nl);
            }
            sb.append(nl);
            sb.append("    @InjectMocks").append(nl);
            sb.append("    private ").append(candidate.className()).append(" subject;").append(nl);
            sb.append(nl);
            sb.append("    // @Test methods go here").append(nl);
            sb.append("}").append(nl);
            if (fieldInjection) {
                sb.append("NOTE: field injection — Mockito injects @Mock fields into @InjectMocks via reflection.").append(nl);
                sb.append("Do NOT use @SpringBootTest or @Autowired in the test.").append(nl);
            }
            sb.append(nl);
        }

        List<String> testImports = filterTestRelevantImports(analysis.importedTypes());
        if (!testImports.isEmpty()) {
            sb.append("IMPORTS TO USE (exact FQNs from source class — do not invent others):").append(nl);
            for (String imp : testImports) {
                sb.append("  ").append(imp).append(nl);
            }
            sb.append(nl);
        }

        if (!analysis.publicMethods().isEmpty()) {
            sb.append("METHODS TO TEST (call subject.<method> with EXACTLY the parameter types listed — do not invent overloads):").append(nl);
            sb.append(buildPublicMethodsBlock(analysis)).append(nl);
            sb.append(nl);
        }

        sb.append("COVERAGE RULES per method:").append(nl);
        sb.append("- assert the actual return value (not just non-null)").append(nl);
        sb.append("- cover each branch (if/else, orElse, ternary, guard clause)").append(nl);
        if (analysis.usesOptional()) {
            sb.append("- mock Optional.of(...) for found and Optional.empty() for not-found paths").append(nl);
            sb.append("- when a method calls orElse(null) and then uses the result without null check,").append(nl);
            sb.append("  the not-found branch throws NullPointerException — use assertThrows(NullPointerException.class, ...) for it").append(nl);
        }
        if (analysis.usesExceptions()) {
            sb.append("- use assertThrows for exception paths").append(nl);
        }
        sb.append("- use verify() on mocks when the observable result is a collaborator call").append(nl);
        sb.append("- CRITICAL — argument matchers: if the method body creates a NEW object internally").append(nl);
        sb.append("  (e.g. `Autor autor = new Autor(); autor.setId(idAutor); repo.findByAutor(autor);`)").append(nl);
        sb.append("  you CANNOT match that instance in the test. Use `any(Autor.class)` in both").append(nl);
        sb.append("  `when(repo.findByAutor(any(Autor.class))).thenReturn(...)` and").append(nl);
        sb.append("  `verify(repo).findByAutor(any(Autor.class))`. Never create a matching instance").append(nl);
        sb.append("  to stub an internally-created object — it will always fail without equals().").append(nl);
        sb.append("- CRITICAL — test data: use the no-arg constructor (`new Type()`) to create test objects,").append(nl);
        sb.append("  then use setters. Do NOT invent multi-arg constructors; they only exist if @AllArgsConstructor").append(nl);
        sb.append("  is visible in the source. Wrong constructors cause compilation errors.").append(nl);
        sb.append("- CRITICAL — non-null fields: when a method body does `obj.setX(param.getX())`, the test param").append(nl);
        sb.append("  MUST have X set to a real non-null value before the call (e.g. `param.setX(\"value\")` for String,").append(nl);
        sb.append("  `param.setX(2023)` for int). Forgetting to set a String field leaves it null, which causes").append(nl);
        sb.append("  NullPointerException in setters that enforce `@Nonnull` constraints.").append(nl);
        sb.append("- use descriptive test method names").append(nl);
        sb.append(nl);

        sb.append("SOURCE CLASS:").append(nl);
        sb.append(sourceCode);

        return sb.toString();
    }

    private List<String> filterTestRelevantImports(List<String> imports) {
        return imports.stream()
                .filter(imp -> imp != null && !imp.isBlank())
                .filter(imp -> !imp.startsWith("org.springframework.beans.factory.annotation."))
                .filter(imp -> !imp.startsWith("org.springframework.stereotype."))
                .filter(imp -> !imp.startsWith("org.springframework.web.bind.annotation."))
                .filter(imp -> !imp.startsWith("jakarta.persistence."))
                .filter(imp -> !imp.startsWith("javax.persistence."))
                .toList();
    }

    private String buildPublicMethodsBlock(ClassAnalysis analysis) {
        StringBuilder builder = new StringBuilder();
        List<MethodAnalysis> methods = analysis.publicMethods();
        for (int i = 0; i < methods.size(); i++) {
            MethodAnalysis method = methods.get(i);
            String signature = method.methodName() + "(" + String.join(", ", method.parameters()) + ") -> " + method.returnType();
            builder.append(i + 1).append(". ").append(signature);
            if (!method.thrownExceptions().isEmpty()) {
                builder.append(" throws ").append(String.join(", ", method.thrownExceptions()));
            }
            if (!method.methodBody().isBlank()) {
                builder.append(nl).append("   body:");
                for (String line : method.methodBody().split("\\R", -1)) {
                    builder.append(nl).append("   ").append(line);
                }
            }
            if (i < methods.size() - 1) {
                builder.append(nl);
            }
        }
        return builder.toString().stripTrailing();
    }

    private static final String nl = System.lineSeparator();
}
