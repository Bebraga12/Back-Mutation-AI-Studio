package com.mutation.mutation_ai_studio.adapters.in.web.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record StartAiTestRunRequest(
        @NotEmpty(message = "classes deve conter ao menos um item")
        List<String> classes
) {
}
