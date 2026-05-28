package com.mutation.mutation_ai_studio.adapters.in.web.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateProjectRequest(
        @NotBlank(message = "name e obrigatorio")
        String name,
        @NotBlank(message = "repositoryPath e obrigatorio")
        String repositoryPath,
        String mavenPath
) {
}
