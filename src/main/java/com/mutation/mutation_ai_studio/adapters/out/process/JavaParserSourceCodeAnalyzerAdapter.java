package com.mutation.mutation_ai_studio.adapters.out.process;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.ImportDeclaration;
import com.mutation.mutation_ai_studio.application.port.out.SourceCodeAnalyzerPort;
import com.mutation.mutation_ai_studio.domain.model.ClassAnalysis;
import com.mutation.mutation_ai_studio.domain.model.JavaClassCandidate;
import com.mutation.mutation_ai_studio.domain.model.MethodAnalysis;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

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
                    sourceCode.contains("throw ") || sourceCode.contains("throws ")
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

        List<String> importedTypes = compilationUnit.getImports().stream()
                .map(ImportDeclaration::getNameAsString)
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
                usesExceptions
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
}
