package com.mutation.mutation_ai_studio.domain.model;

import java.util.List;

public record MethodAnalysis(
        String methodName,
        String returnType,
        List<String> parameters,
        List<String> thrownExceptions,
        String methodBody
) {
}
