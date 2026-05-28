package com.mutation.mutation_ai_studio.adapters.in.web.dto;

public record ApiDiffLine(
        int line,
        String code,
        String kind
) {
}
