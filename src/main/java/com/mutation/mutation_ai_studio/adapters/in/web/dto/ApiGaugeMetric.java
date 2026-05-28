package com.mutation.mutation_ai_studio.adapters.in.web.dto;

public record ApiGaugeMetric(
        String id,
        String label,
        String helper,
        int before,
        int after,
        String tone
) {
}
