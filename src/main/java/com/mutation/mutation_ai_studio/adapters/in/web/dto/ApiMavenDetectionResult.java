package com.mutation.mutation_ai_studio.adapters.in.web.dto;

public record ApiMavenDetectionResult(
        boolean found,
        String path,
        String version,
        String message
) {
}
