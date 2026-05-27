package com.mutation.mutation_ai_studio.application.usecase;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.mutation.mutation_ai_studio.domain.model.ClassTestPrompt;

import java.util.LinkedHashSet;
import java.util.List;

final class GeneratedTestSourceNormalizer {

    private static final List<String> COMMON_IMPORTS = List.of(
            "java.util.Optional",
            "java.util.List",
            "java.util.ArrayList",
            "org.junit.jupiter.api.Test",
            "org.junit.jupiter.api.BeforeEach",
            "org.junit.jupiter.api.extension.ExtendWith",
            "org.mockito.Mock",
            "org.mockito.InjectMocks",
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

        try {
            CompilationUnit compilationUnit = StaticJavaParser.parse(sanitized);
            compilationUnit.setPackageDeclaration(prompt.analysis().packageName());
            normalizeTypeName(compilationUnit, prompt.className() + "Test");
            ensureImports(compilationUnit, prompt.analysis().importedTypes());
            ensureCommonImports(compilationUnit);
            ensureMockitoExtension(compilationUnit);
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
     * Ensures the test class is properly set up for Mockito mock injection.
     * If neither @ExtendWith(MockitoExtension.class) nor MockitoAnnotations.openMocks()
     * is present, adds the @ExtendWith annotation to the class declaration.
     */
    private static void ensureMockitoExtension(CompilationUnit compilationUnit) {
        ClassOrInterfaceDeclaration testClass = compilationUnit
                .findFirst(ClassOrInterfaceDeclaration.class)
                .orElse(null);
        if (testClass == null) {
            return;
        }

        // Already has @ExtendWith on the class?
        boolean hasExtendWith = testClass.getAnnotations().stream()
                .anyMatch(a -> a.getNameAsString().equals("ExtendWith"));
        if (hasExtendWith) {
            return;
        }

        // Already uses MockitoAnnotations.openMocks() in a @BeforeEach or constructor?
        boolean hasOpenMocks = testClass.getMethods().stream()
                .anyMatch(m -> m.toString().contains("MockitoAnnotations.openMocks")
                        || m.toString().contains("MockitoAnnotations.initMocks"));
        if (hasOpenMocks) {
            return;
        }

        // Neither found — add @ExtendWith(MockitoExtension.class) to the class
        NormalAnnotationExpr extendWith = new NormalAnnotationExpr(
                new com.github.javaparser.ast.expr.Name("ExtendWith"),
                new NodeList<>(new MemberValuePair("value",
                        new ClassExpr(StaticJavaParser.parseType("MockitoExtension"))))
        );
        testClass.addAnnotation(extendWith);
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
