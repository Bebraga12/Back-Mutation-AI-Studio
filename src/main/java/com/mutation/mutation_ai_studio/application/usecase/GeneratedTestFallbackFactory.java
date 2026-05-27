package com.mutation.mutation_ai_studio.application.usecase;

import com.mutation.mutation_ai_studio.domain.model.ClassTestPrompt;

import java.util.List;

final class GeneratedTestFallbackFactory {

    private static final List<String> COMMON_IMPORTS = List.of(
            "org.junit.jupiter.api.Test",
            "org.junit.jupiter.api.extension.ExtendWith",
            "org.mockito.InjectMocks",
            "org.mockito.junit.jupiter.MockitoExtension"
    );

    private GeneratedTestFallbackFactory() {
    }

    static String generate(ClassTestPrompt prompt) {
        StringBuilder builder = new StringBuilder();
        builder.append("package ").append(prompt.analysis().packageName()).append(";\n\n");

        for (String importName : prompt.analysis().importedTypes()) {
            if (importName == null || importName.isBlank() || isProductionSideImport(importName)) {
                continue;
            }
            builder.append("import ").append(importName).append(";\n");
        }

        for (String importName : COMMON_IMPORTS) {
            builder.append("import ").append(importName).append(";\n");
        }

        if (!prompt.dependencies().isEmpty()) {
            builder.append("import org.mockito.Mock;\n");
        }

        builder.append("\nimport static org.junit.jupiter.api.Assertions.assertNotNull;\n");
        builder.append("import static org.mockito.Mockito.*;\n\n");

        builder.append("@ExtendWith(MockitoExtension.class)\n");
        builder.append("public class ").append(prompt.className()).append("Test {\n\n");

        for (String dep : prompt.dependencies()) {
            builder.append("    @Mock\n");
            builder.append("    private ").append(dep).append(";\n\n");
        }

        builder.append("    @InjectMocks\n");
        builder.append("    private ").append(prompt.className()).append(" subject;\n\n");

        builder.append("    @Test\n");
        builder.append("    void deveInstanciarSubjeto() {\n");
        builder.append("        assertNotNull(subject);\n");
        builder.append("    }\n");
        builder.append("}\n");

        return builder.toString();
    }

    private static boolean isProductionSideImport(String qualifiedName) {
        return qualifiedName.startsWith("org.springframework.beans.factory.annotation.")
                || qualifiedName.startsWith("org.springframework.stereotype.")
                || qualifiedName.startsWith("org.springframework.web.bind.annotation.")
                || qualifiedName.startsWith("jakarta.persistence.")
                || qualifiedName.startsWith("javax.persistence.");
    }
}
