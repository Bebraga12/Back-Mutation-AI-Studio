package com.mutation.mutation_ai_studio.adapters.in.web.dto;

import java.util.List;

public record AiTestClassResult(
        String className,
        String fqn,
        boolean passed,
        String testPath,
        List<String> errors
) {
}
