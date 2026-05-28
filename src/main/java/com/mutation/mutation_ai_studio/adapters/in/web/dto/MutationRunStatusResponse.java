package com.mutation.mutation_ai_studio.adapters.in.web.dto;

import java.util.List;

public record MutationRunStatusResponse(
        String runId,
        String projectId,
        String status,
        String message,
        boolean simulated,
        String startedAt,
        String finishedAt,
        Long durationMs,
        Integer exitCode,
        List<String> command,
        List<String> output
) {
}
