package com.mutation.mutation_ai_studio.adapters.in.web.dto;

public record ApiProjectClass(
        String id,
        String packageName,
        String statusLabel,
        String statusTone,
        int estimatedMutants,
        boolean preselected
) {
}
