package com.mutation.mutation_ai_studio.adapters.out.process;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.mutation.mutation_ai_studio.application.port.out.SourceCodeAnalyzerPort;
import com.mutation.mutation_ai_studio.domain.model.ClassAnalysis;
import com.mutation.mutation_ai_studio.domain.model.JavaClassCandidate;
import com.mutation.mutation_ai_studio.domain.model.MethodAnalysis;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class JavaParserSourceCodeAnalyzerAdapter implements SourceCodeAnalyzerPort {

    @Override
    public ClassAnalysis analyze(Path projectRoot, JavaClassCandidate candidate, String sourceCode) {
        try {
            return parseAndAnalyze(candidate, sourceCode);
        } catch (RuntimeException ex) {
            // JavaParser pode falhar em código com sintaxe não suportada (Java preview features,
            // annotations exóticas, etc.). Retorna análise mínima para não travar a geração do lote.
            return new ClassAnalysis(
                    candidate.className(),
                    candidate.packageName(),
                    "nenhum construtor explícito identificado",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    sourceCode.contains("Optional<") || sourceCode.contains("Optional."),
                    sourceCode.contains("throw ") || sourceCode.contains("throws "),
                    List.of()
            );
        }
    }

    private ClassAnalysis parseAndAnalyze(JavaClassCandidate candidate, String sourceCode) {
        CompilationUnit compilationUnit = StaticJavaParser.parse(sourceCode);
        ClassOrInterfaceDeclaration declaration = compilationUnit.findFirst(ClassOrInterfaceDeclaration.class)
                .orElseThrow(() -> new IllegalStateException("Classe não encontrada para análise: " + candidate.fullyQualifiedName()));

        ConstructorDeclaration primaryConstructor = declaration.getConstructors().stream()
                .findFirst()
                .orElse(null);

        List<String> constructorDependencies = primaryConstructor != null
                ? extractConstructorDependencies(primaryConstructor)
                : List.of();

        String constructorSignature = primaryConstructor != null
                ? primaryConstructor.getDeclarationAsString(false, false, false)
                : "nenhum construtor explícito identificado";

        List<String> fieldDependencies = declaration.getFields().stream()
                .filter(field -> !field.isStatic())
                .map(this::formatField)
                .toList();

        List<MethodAnalysis> publicMethods = declaration.getMethods().stream()
                .filter(MethodDeclaration::isPublic)
                .map(this::toMethodAnalysis)
                .toList();

        List<String> explicitImports = compilationUnit.getImports().stream()
                .map(ImportDeclaration::getNameAsString)
                .toList();

        // Same-package types: classes referenced in fields, parameters, or method bodies
        // that are NOT in the explicit import list. Because they're in the same package as the
        // source class, Java doesn't require an explicit import — but the generated test DOES
        // need one. We infer these by scanning all ClassOrInterfaceType nodes in the AST and
        // adding fully-qualified names for any simple type that has no matching import.
        List<String> samePackageTypes = extractSamePackageTypeImports(
                compilationUnit, candidate.packageName(), explicitImports);

        List<String> importedTypes = new ArrayList<>(explicitImports);
        importedTypes.addAll(samePackageTypes);

        // Collect non-public (private/protected/package-private) method names so the prompt
        // can explicitly warn the AI not to call them from the test.
        List<String> nonPublicMethodNames = declaration.getMethods().stream()
                .filter(m -> !m.isPublic())
                .map(MethodDeclaration::getNameAsString)
                .distinct()
                .toList();

        boolean usesOptional = sourceCode.contains("Optional<") || sourceCode.contains("Optional.");
        boolean usesExceptions = sourceCode.contains("throw ") || sourceCode.contains("throws ");

        return new ClassAnalysis(
                candidate.className(),
                candidate.packageName(),
                constructorSignature,
                constructorDependencies,
                fieldDependencies,
                publicMethods,
                importedTypes,
                usesOptional,
                usesExceptions,
                nonPublicMethodNames
        );
    }

    private List<String> extractConstructorDependencies(ConstructorDeclaration constructor) {
        return constructor.getParameters().stream()
                .map(parameter -> parameter.getType().asString() + " " + parameter.getNameAsString())
                .toList();
    }

    private String formatField(FieldDeclaration field) {
        return field.getElementType().asString() + " " + field.getVariable(0).getNameAsString();
    }

    private MethodAnalysis toMethodAnalysis(MethodDeclaration method) {
        List<String> parameters = method.getParameters().stream()
                .map(parameter -> parameter.getType().asString() + " " + parameter.getNameAsString())
                .toList();

        List<String> thrownExceptions = method.getThrownExceptions().stream()
                .map(Object::toString)
                .toList();

        String methodBody = method.getBody()
                .map(body -> body.toString().trim())
                .orElse("");

        return new MethodAnalysis(
                method.getNameAsString(),
                method.getType().asString(),
                parameters,
                thrownExceptions,
                methodBody
        );
    }

    /**
     * Scans all {@link ClassOrInterfaceType} nodes in the production source and returns
     * fully-qualified names for any simple type that has no matching explicit import.
     *
     * <p>In Java, classes in the same package do not require explicit {@code import} statements.
     * When the AI generates a test for such a class it may use types like {@code new Usuario()}
     * that are in the same package as the source — but the test lives in that same package only
     * by convention (and Maven test directory layout), so the AI actually needs the import. By
     * adding {@code packageName.TypeName} to {@code importedTypes}, the normalizer's
     * {@code ensureImports()} step will insert the import automatically.
     *
     * <p>Known Java/framework built-in names are excluded to avoid flooding the import list
     * with noise. Only simple names starting with an uppercase letter that are NOT already
     * covered by an explicit import are included.
     */
    private List<String> extractSamePackageTypeImports(
            CompilationUnit compilationUnit, String packageName, List<String> explicitImports) {

        // Simple names already covered by explicit imports
        Set<String> importedSimpleNames = explicitImports.stream()
                .map(fqn -> fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn)
                .collect(Collectors.toSet());

        // Well-known types that are definitely NOT in the user's package
        Set<String> builtinNames = new HashSet<>(Set.of(
                // java.lang
                "String", "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Character", "Short",
                "Object", "Number", "Comparable", "Iterable", "Cloneable", "Enum",
                "Exception", "RuntimeException", "IllegalArgumentException", "IllegalStateException",
                "NullPointerException", "UnsupportedOperationException", "IndexOutOfBoundsException",
                "ArithmeticException", "ClassCastException", "StringBuilder", "StringBuffer",
                "Math", "System", "Thread",
                // java.util
                "List", "ArrayList", "LinkedList", "Map", "HashMap", "LinkedHashMap", "TreeMap",
                "Set", "HashSet", "LinkedHashSet", "TreeSet", "Optional", "Stream", "Collections",
                "Arrays", "Iterator", "Collection",
                // JUnit / Mockito
                "Test", "BeforeEach", "AfterEach", "BeforeAll", "AfterAll",
                "ExtendWith", "Mock", "InjectMocks", "Spy", "Captor", "Answers",
                "MockitoExtension", "ArgumentCaptor", "MockitoAnnotations",
                // Spring
                "Autowired", "Service", "Component", "Repository", "Controller", "RestController",
                "Configuration", "Bean", "Qualifier", "Value",
                // Annotation meta
                "Override", "Deprecated", "SuppressWarnings", "FunctionalInterface"
        ));

        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        compilationUnit.findAll(ClassOrInterfaceType.class).forEach(type -> {
            String name = type.getNameAsString();
            // Skip single-character names (generic type parameters like T, E, K, V)
            if (name.length() <= 1) return;
            // Skip if not starting with uppercase (not a class name)
            if (!Character.isUpperCase(name.charAt(0))) return;
            // Skip if already covered by an explicit import
            if (importedSimpleNames.contains(name)) return;
            // Skip well-known built-in names
            if (builtinNames.contains(name)) return;
            // Skip scoped types (e.g. Foo.Bar — Foo is the qualifier, Bar is the inner class)
            if (type.getScope().isPresent()) return;

            String fqn = packageName + "." + name;
            if (seen.add(fqn)) {
                result.add(fqn);
            }
        });

        return result;
    }
}
