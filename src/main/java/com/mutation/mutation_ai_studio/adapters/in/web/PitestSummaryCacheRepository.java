package com.mutation.mutation_ai_studio.adapters.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@Repository
public class PitestSummaryCacheRepository {

    private static final Path SUMMARY_PATH = Path.of(".mutation-ai", "pitest-summary.json");

    private final ObjectMapper objectMapper;

    public PitestSummaryCacheRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<PitestSummaryCache> load(Path projectRoot) {
        Path summaryFile = projectRoot.resolve(SUMMARY_PATH);
        if (!Files.exists(summaryFile) || !Files.isRegularFile(summaryFile)) {
            return Optional.empty();
        }

        try {
            return Optional.of(objectMapper.readValue(summaryFile.toFile(), PitestSummaryCache.class));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public void save(Path projectRoot, PitestMetrics metrics) {
        Path summaryFile = projectRoot.resolve(SUMMARY_PATH);
        try {
            Files.createDirectories(summaryFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(summaryFile.toFile(),
                    new PitestSummaryCache(
                            metrics.mutationScore(),
                            metrics.total(),
                            metrics.killed(),
                            metrics.survivorCount(),
                            metrics.noCoverage(),
                            metrics.survivorRate(),
                            metrics.killedRate(),
                            metrics.reportFile(),
                            java.time.Instant.now().toString()));
        } catch (Exception ignored) {
            // Cache auxiliar; falha nao deve bloquear fluxo principal.
        }
    }
}
