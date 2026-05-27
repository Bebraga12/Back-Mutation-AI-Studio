package com.mutation.mutation_ai_studio.domain.model;

import java.util.List;

public record ClassAnalysis(
        String className,
        String packageName,
        String constructorSignature,
        List<String> constructorDependencies,
        List<String> fieldDependencies,
        List<MethodAnalysis> publicMethods,
        List<String> importedTypes,
        boolean usesOptional,
        boolean usesExceptions,
        /** Nomes de métodos não-públicos declarados na fonte (private/protected/package-private).
         *  Usado para avisar o AI a não tentar chamá-los diretamente no teste. */
        List<String> nonPublicMethodNames
) {
}
