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
        /** Nomes de métodos privados declarados na fonte. Protected/package-private ficam em
         *  publicMethods, pois são chamáveis a partir do teste (mesmo pacote). Usado para avisar
         *  o AI a não tentar chamar métodos privados diretamente no teste. */
        List<String> nonPublicMethodNames
) {
}
