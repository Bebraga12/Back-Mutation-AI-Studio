package com.mutation.mutation_ai_studio.adapters.in.web.project;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.ApiProject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Repository
public class ProjectCatalogFileRepository {

    private static final TypeReference<List<ApiProject>> PROJECT_LIST_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final Path storagePath;

    public ProjectCatalogFileRepository(
            ObjectMapper objectMapper,
            @Value("${workspace.projects.storage-path:data/projects/projects.json}") String storagePath) {
        this.objectMapper = objectMapper;
        this.storagePath = Path.of(storagePath).toAbsolutePath().normalize();
    }

    public synchronized List<ApiProject> loadProjects() {
        if (!Files.exists(storagePath)) {
            return List.of();
        }

        try {
            return List.copyOf(objectMapper.readValue(storagePath.toFile(), PROJECT_LIST_TYPE));
        } catch (IOException exception) {
            throw new IllegalStateException("Falha ao ler arquivo de projetos: " + storagePath, exception);
        }
    }

    public synchronized void saveProjects(List<ApiProject> projects) {
        try {
            Path parent = storagePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(storagePath.toFile(), new ArrayList<>(projects));
        } catch (IOException exception) {
            throw new IllegalStateException("Falha ao salvar arquivo de projetos: " + storagePath, exception);
        }
    }
}
