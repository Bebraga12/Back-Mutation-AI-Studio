package com.mutation.mutation_ai_studio.application.usecase;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.type.ReferenceType;
import com.mutation.mutation_ai_studio.domain.model.ClassTestPrompt;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

final class GeneratedTestSourceNormalizer {

    private static final List<String> COMMON_IMPORTS = List.of(
            "java.util.Optional",
            "java.util.List",
            "java.util.ArrayList",
            "java.util.Set",
            "java.util.Map",
            "java.util.HashMap",
            "org.junit.jupiter.api.Test",
            "org.junit.jupiter.api.BeforeEach",
            "org.junit.jupiter.api.extension.ExtendWith",
            "org.mockito.Mock",
            "org.mockito.InjectMocks",
            "org.mockito.Answers",
            "org.mockito.junit.jupiter.MockitoExtension"
    );

    private static final List<String> COMMON_STATIC_IMPORTS = List.of(
            "org.junit.jupiter.api.Assertions.*",
            "org.mockito.Mockito.*",
            "org.mockito.ArgumentMatchers.*"
    );

    private GeneratedTestSourceNormalizer() {
    }

    static String normalize(String generatedCode, ClassTestPrompt prompt) {
        String sanitized = GeneratedTestSanitizer.sanitize(generatedCode);
        if (sanitized.isBlank()) {
            return GeneratedTestFallbackFactory.generate(prompt);
        }

        sanitized = sanitized.replace("import org.mockito.MockitoExtension;", "import org.mockito.junit.jupiter.MockitoExtension;");
        sanitized = sanitized.replace("org.mockito.MockitoExtension", "org.mockito.junit.jupiter.MockitoExtension");

        // Checked Exception → RuntimeException: thenThrow/doThrow with 'new Exception' causes
        // MockitoException when the mocked method doesn't declare 'throws Exception'.
        // Replacing with RuntimeException (unchecked) is always safe for mocks that don't
        // explicitly declare checked exceptions.
        sanitized = replaceCheckedExceptionInStubs(sanitized);

        try {
            CompilationUnit compilationUnit = StaticJavaParser.parse(sanitized);
            compilationUnit.setPackageDeclaration(prompt.analysis().packageName());
            normalizeTypeName(compilationUnit, prompt.className() + "Test");
            ensureImports(compilationUnit, prompt.analysis().importedTypes());
            ensureSpringValidationImports(compilationUnit);
            ensureCommonImports(compilationUnit);
            removeProductionAnnotationsFromTestClass(compilationUnit);
            ensureMockitoExtension(compilationUnit);
            ensureTestMethodsThrowException(compilationUnit);
            return compilationUnit.toString();
        } catch (RuntimeException ex) {
            return GeneratedTestFallbackFactory.generate(prompt);
        }
    }

    private static void normalizeTypeName(CompilationUnit compilationUnit, String expectedTestClassName) {
        compilationUnit.findFirst(ClassOrInterfaceDeclaration.class)
                .ifPresent(declaration -> {
                    if (!expectedTestClassName.equals(declaration.getNameAsString())) {
                        declaration.setName(expectedTestClassName);
                    }
                });
    }

    private static void ensureImports(CompilationUnit compilationUnit, List<String> requiredImports) {
        LinkedHashSet<String> existingImports = new LinkedHashSet<>();
        compilationUnit.getImports().forEach(importDeclaration -> existingImports.add(importDeclaration.getNameAsString()));

        for (String qualifiedName : requiredImports) {
            if (qualifiedName == null || qualifiedName.isBlank()) {
                continue;
            }
            if (isProductionSideImport(qualifiedName)) {
                continue;
            }
            if (existingImports.contains(qualifiedName)) {
                continue;
            }
            compilationUnit.addImport(qualifiedName);
        }
    }

    private static boolean isProductionSideImport(String qualifiedName) {
        return qualifiedName.startsWith("org.springframework.beans.factory.annotation.")
                || qualifiedName.startsWith("org.springframework.stereotype.")
                || qualifiedName.startsWith("org.springframework.web.bind.annotation.")
                || qualifiedName.startsWith("jakarta.persistence.")
                || qualifiedName.startsWith("javax.persistence.");
    }

    /**
     * Pattern matching a @BeforeEach body that ONLY calls MockitoAnnotations.openMocks(this)
     * or MockitoAnnotations.initMocks(this) — i.e., no other statements.
     */
    private static final Pattern ONLY_OPEN_MOCKS_BODY = Pattern.compile(
            "\\{\\s*MockitoAnnotations\\.(?:openMocks|initMocks)\\(this\\);\\s*\\}"
    );

    /**
     * Ensures the test class is properly set up for Mockito mock injection.
     *
     * Strategy:
     * 1. Collect all @BeforeEach methods that use MockitoAnnotations.openMocks / initMocks.
     * 2. If any @BeforeEach contains ONLY the openMocks call → remove that method.
     * 3. If openMocks is still used (alongside other code) after step 2 →
     *    keep it but ensure org.mockito.MockitoAnnotations is imported.
     * 4. If @ExtendWith(MockitoExtension.class) is not yet on the class → add it.
     *
     * Note: the cleanup runs even when @ExtendWith is already present — the AI sometimes
     * generates BOTH @ExtendWith AND a redundant openMocks @BeforeEach without the import.
     */
    private static void ensureMockitoExtension(CompilationUnit compilationUnit) {
        ClassOrInterfaceDeclaration testClass = compilationUnit
                .findFirst(ClassOrInterfaceDeclaration.class)
                .orElse(null);
        if (testClass == null) {
            return;
        }

        // Step 1: collect @BeforeEach methods that use openMocks / initMocks
        List<MethodDeclaration> openMocksMethods = testClass.getMethods().stream()
                .filter(m -> m.isAnnotationPresent("BeforeEach"))
                .filter(m -> {
                    String body = m.getBody().map(b -> b.toString().trim()).orElse("");
                    return body.contains("MockitoAnnotations.openMocks")
                            || body.contains("MockitoAnnotations.initMocks");
                })
                .toList();

        if (!openMocksMethods.isEmpty()) {
            // Step 2: remove @BeforeEach methods that ONLY contain the openMocks call
            openMocksMethods.stream()
                    .filter(m -> {
                        String body = m.getBody().map(b -> b.toString().trim()).orElse("");
                        return ONLY_OPEN_MOCKS_BODY.matcher(body).matches();
                    })
                    .forEach(MethodDeclaration::remove);

            // Step 3: if openMocks is still used, ensure import
            boolean stillHasOpenMocks = testClass.getMethods().stream()
                    .anyMatch(m -> m.toString().contains("MockitoAnnotations.openMocks")
                            || m.toString().contains("MockitoAnnotations.initMocks"));
            if (stillHasOpenMocks) {
                compilationUnit.addImport("org.mockito.MockitoAnnotations");
            }
        }

        // Step 4: add @ExtendWith(MockitoExtension.class) if not already on the class
        boolean hasExtendWith = testClass.getAnnotations().stream()
                .anyMatch(a -> a.getNameAsString().equals("ExtendWith"));
        if (!hasExtendWith) {
            addExtendWithAnnotation(testClass);
        }
    }

    private static void addExtendWithAnnotation(ClassOrInterfaceDeclaration testClass) {
        NormalAnnotationExpr extendWith = new NormalAnnotationExpr(
                new com.github.javaparser.ast.expr.Name("ExtendWith"),
                new NodeList<>(new MemberValuePair("value",
                        new ClassExpr(StaticJavaParser.parseType("MockitoExtension"))))
        );
        testClass.addAnnotation(extendWith);
    }

    /**
     * Replaces {@code new Exception(...)} in Mockito stub calls (thenThrow / doThrow) with
     * {@code new RuntimeException(...)}.
     *
     * <p>The AI frequently uses {@code thenThrow(new Exception())} for mocked methods that do NOT
     * declare {@code throws Exception}. Mockito's strict mode rejects checked exceptions on such
     * methods with "Checked exception is invalid for this method!" — a runtime error that fails every
     * test. Replacing {@code Exception} with {@code RuntimeException} is safe because:
     * <ul>
     *   <li>RuntimeException is unchecked — always valid for any Mockito stub.</li>
     *   <li>The production {@code catch (Exception e)} block catches both checked and unchecked
     *       exceptions, so the branch-coverage goal is preserved.</li>
     * </ul>
     * Only occurrences that immediately follow {@code thenThrow(} or {@code doThrow(} are changed,
     * minimising false positives.
     */
    private static String replaceCheckedExceptionInStubs(String code) {
        // Match thenThrow(new Exception or doThrow(new Exception immediately followed by '(' or ')'
        // We replace only "new Exception" (the base class) — subclasses like IOException,
        // IllegalArgumentException etc. are left alone since the developer may have had a reason.
        code = code.replace("thenThrow(new Exception()", "thenThrow(new RuntimeException())")
                   .replace("thenThrow(new Exception(\"", "thenThrow(new RuntimeException(\"")
                   .replace("doThrow(new Exception()", "doThrow(new RuntimeException())")
                   .replace("doThrow(new Exception(\"", "doThrow(new RuntimeException(\"");
        return code;
    }

    /**
     * Adds well-known companion imports that the AI frequently needs but that the production class
     * often doesn't import explicitly (because they are accessed via method return types, not declared
     * as field/parameter types).
     *
     * <ul>
     *   <li>{@code org.springframework.validation.BindingResult} — needed in tests that mock
     *       {@code MethodArgumentNotValidException.getBindingResult()}.  The production source uses
     *       {@code ex.getBindingResult()} but never imports the return type.
     *   <li>{@code org.springframework.security.core.userdetails.UserDetails} — needed in tests that
     *       mock the result of {@code UserDetailsService.loadUserByUsername()}.  The production class
     *       usually only imports {@code UserDetailsService}, not the value it returns.
     * </ul>
     */
    private static void ensureSpringValidationImports(CompilationUnit compilationUnit) {
        String sourceText = compilationUnit.toString();

        boolean importsManve = compilationUnit.getImports().stream()
                .anyMatch(i -> i.getNameAsString().contains("MethodArgumentNotValidException"));
        if (importsManve && sourceText.contains("BindingResult")) {
            boolean alreadyImported = compilationUnit.getImports().stream()
                    .anyMatch(i -> i.getNameAsString().equals("org.springframework.validation.BindingResult"));
            if (!alreadyImported) {
                compilationUnit.addImport("org.springframework.validation.BindingResult");
            }
        }

        boolean importsUserDetailsService = compilationUnit.getImports().stream()
                .anyMatch(i -> i.getNameAsString().contains("UserDetailsService"));
        if (importsUserDetailsService && sourceText.contains("UserDetails")) {
            boolean alreadyImported = compilationUnit.getImports().stream()
                    .anyMatch(i -> i.getNameAsString().equals("org.springframework.security.core.userdetails.UserDetails"));
            if (!alreadyImported) {
                compilationUnit.addImport("org.springframework.security.core.userdetails.UserDetails");
            }
        }
    }

    /**
     * Removes Spring/Jakarta production-side annotations from the test class declaration.
     *
     * <p>The AI sometimes annotates the generated test class with production annotations such as
     * {@code @ControllerAdvice}, {@code @Service}, {@code @RestController}, etc. These annotations
     * belong to the production class under test, not the test class. In the test context:
     * <ul>
     *   <li>Their imports are filtered by {@link #isProductionSideImport} — so they wouldn't be
     *       re-added even if missing.</li>
     *   <li>When the AI includes the annotation AND the import, the normalizer removes the import
     *       but leaves the annotation, causing a {@code cannot find symbol: class ControllerAdvice}
     *       compilation error.</li>
     * </ul>
     * This step removes any such annotations from the primary test class declaration.
     */
    private static final java.util.Set<String> PRODUCTION_CLASS_ANNOTATIONS = java.util.Set.of(
            "ControllerAdvice", "RestControllerAdvice",
            "RestController", "Controller",
            "Service", "Component", "Repository",
            "Configuration", "SpringBootApplication"
    );

    private static void removeProductionAnnotationsFromTestClass(CompilationUnit compilationUnit) {
        compilationUnit.findFirst(ClassOrInterfaceDeclaration.class).ifPresent(cls ->
            cls.getAnnotations().removeIf(ann ->
                PRODUCTION_CLASS_ANNOTATIONS.contains(ann.getNameAsString()))
        );
    }

    /**
     * Adds {@code throws Exception} to every {@code @Test} method that does not already declare a
     * thrown exception.
     *
     * <p>Tests often call methods that declare {@code throws IOException}, {@code throws
     * ServletException}, etc. Without propagating these from the test method, Java requires them to
     * be caught — but adding try/catch inside tests is verbose and hides assertion failures. The
     * standard practice is to declare {@code throws Exception} on the test method itself, which Java
     * accepts because JUnit 5 handles any throwable from a test method as a test failure.
     */
    private static void ensureTestMethodsThrowException(CompilationUnit compilationUnit) {
        compilationUnit.findFirst(ClassOrInterfaceDeclaration.class).ifPresent(cls ->
            cls.getMethods().stream()
                .filter(m -> m.isAnnotationPresent("Test"))
                .filter(m -> m.getThrownExceptions().isEmpty())
                .forEach(m -> {
                    ReferenceType exceptionType = StaticJavaParser.parseClassOrInterfaceType("Exception");
                    m.addThrownException(exceptionType);
                })
        );
    }

    private static void ensureCommonImports(CompilationUnit compilationUnit) {
        LinkedHashSet<String> existingImports = new LinkedHashSet<>();
        compilationUnit.getImports().forEach(importDeclaration -> existingImports.add(importDeclaration.getNameAsString()));

        for (String qualifiedName : COMMON_IMPORTS) {
            if (existingImports.contains(qualifiedName)) {
                continue;
            }
            compilationUnit.addImport(qualifiedName);
        }

        for (String qualifiedName : COMMON_STATIC_IMPORTS) {
            // JavaParser's addImport(name, static, asterisk) espera o nome SEM ".*"
            // — ele mesmo concatena o ".*" baseado no parâmetro isAsterisk.
            String name = qualifiedName.endsWith(".*")
                    ? qualifiedName.substring(0, qualifiedName.length() - 2)
                    : qualifiedName;
            if (existingImports.contains(name)) {
                continue;
            }
            compilationUnit.addImport(name, true, true);
        }
    }

}
