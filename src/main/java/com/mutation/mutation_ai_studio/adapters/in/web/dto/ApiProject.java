package com.mutation.mutation_ai_studio.adapters.in.web.dto;

public record ApiProject(
        String id,
        String name,
        String repositoryPath,
        String mavenPath,
        int lastMutationScore,
        String updatedAt
) {
}
