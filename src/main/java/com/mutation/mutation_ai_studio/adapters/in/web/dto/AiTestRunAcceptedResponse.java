package com.mutation.mutation_ai_studio.adapters.in.web.dto;

public record AiTestRunAcceptedResponse(
        String runId,
        String status,
        String message
) {
}
