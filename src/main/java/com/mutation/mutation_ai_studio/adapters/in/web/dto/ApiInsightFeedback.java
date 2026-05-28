package com.mutation.mutation_ai_studio.adapters.in.web.dto;

public record ApiInsightFeedback(
        String title,
        String detail,
        String recommendation,
        String tone
) {
}
