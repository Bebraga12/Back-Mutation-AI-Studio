package com.mutation.mutation_ai_studio.adapters.in.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Repository
public class BaselineMutantsCacheRepository {

    private static final Path BASELINE_MUTANTS_PATH = Path.of(".mutation-ai", "baseline-mutants.json");

    private final ObjectMapper objectMapper;

    public BaselineMutantsCacheRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Integer> load(Path projectRoot) {
        Path stateFile = projectRoot.resolve(BASELINE_MUTANTS_PATH);
        if (!Files.exists(stateFile) || !Files.isRegularFile(stateFile)) {
            return Map.of();
        }

        try {
            return objectMapper.readValue(stateFile.toFile(), new TypeReference<Map<String, Integer>>() {
            });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    public void save(Path projectRoot, Map<String, Integer> perClassMutantCounts) {
        if (perClassMutantCounts == null || perClassMutantCounts.isEmpty()) {
            return;
        }

        Path stateFile = projectRoot.resolve(BASELINE_MUTANTS_PATH);
        try {
            Files.createDirectories(stateFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(stateFile.toFile(), perClassMutantCounts);
        } catch (Exception ignored) {
            // Cache auxiliar; falha nao deve bloquear fluxo principal.
        }
    }
}
