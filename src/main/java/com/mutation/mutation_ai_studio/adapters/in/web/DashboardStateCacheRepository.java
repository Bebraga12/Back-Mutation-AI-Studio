package com.mutation.mutation_ai_studio.adapters.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

@Repository
public class DashboardStateCacheRepository {

    private static final Path DASHBOARD_STATE_PATH = Path.of(".mutation-ai", "dashboard-state.json");

    private final ObjectMapper objectMapper;

    public DashboardStateCacheRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<DashboardStateCache> load(Path projectRoot) {
        Path stateFile = projectRoot.resolve(DASHBOARD_STATE_PATH);
        if (!Files.exists(stateFile) || !Files.isRegularFile(stateFile)) {
            return Optional.empty();
        }

        try {
            return Optional.of(objectMapper.readValue(stateFile.toFile(), DashboardStateCache.class));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public void save(Path projectRoot, DashboardStateCache state) {
        Path stateFile = projectRoot.resolve(DASHBOARD_STATE_PATH);
        try {
            Files.createDirectories(stateFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(stateFile.toFile(),
                    new DashboardStateCache(
                            state.gaugeMetrics(),
                            state.insights(),
                            state.diffSnapshot(),
                            state.durationMs(),
                            Instant.now().toString()));
        } catch (Exception ignored) {
            // Cache auxiliar; falha nao deve bloquear fluxo principal.
        }
    }
}
