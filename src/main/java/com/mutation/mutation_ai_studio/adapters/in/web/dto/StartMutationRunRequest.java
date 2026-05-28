package com.mutation.mutation_ai_studio.adapters.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record StartMutationRunRequest(
        @NotBlank(message = "projectId e obrigatorio")
        String projectId,
        String mavenPath,
        @NotEmpty(message = "classes deve conter ao menos um item")
        List<@NotBlank(message = "classId invalido") String> classes
) {
}
