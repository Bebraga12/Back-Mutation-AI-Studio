package com.mutation.mutation_ai_studio.adapters.in.web.dto;

import java.util.List;

public record AiTestRunStatusResponse(
        String runId,
        String projectId,
        String status,
        String message,
        int totalClasses,
        int passed,
        int failed,
        List<AiTestClassResult> results
) {
}
