package com.mutation.mutation_ai_studio.adapters.in.web.dto;

public record MutationRunAcceptedResponse(
        String runId,
        String status,
        String message,
        boolean simulated
) {
}
