package com.mutation.mutation_ai_studio.adapters.in.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

final class CommandExecutor {

    private final int runTimeoutMinutes;
    private final int maxOutputLines;

    CommandExecutor(int runTimeoutMinutes, int maxOutputLines) {
        this.runTimeoutMinutes = runTimeoutMinutes;
        this.maxOutputLines = maxOutputLines;
    }

    CommandExecutionResult execute(Path repositoryRoot, List<String> command) throws Exception {
        Path logFile = Files.createTempFile("mutation-run-", ".log");
        long startedAt = System.currentTimeMillis();

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(repositoryRoot.toFile());
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(logFile.toFile());

            Process process = processBuilder.start();
            boolean completed = process.waitFor(runTimeoutMinutes, TimeUnit.MINUTES);

            int exitCode;
            if (!completed) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                exitCode = -1;
            } else {
                exitCode = process.exitValue();
            }

            long durationMs = System.currentTimeMillis() - startedAt;
            List<String> outputLines = readOutputLines(logFile);

            return new CommandExecutionResult(
                    List.copyOf(command),
                    exitCode,
                    !completed,
                    durationMs,
                    outputLines
            );
        } finally {
            try {
                Files.deleteIfExists(logFile);
            } catch (Exception ignored) {
                // Ignora limpeza de arquivo temporario.
            }
        }
    }

    String extractLastNonBlankLine(List<String> output) {
        for (int index = output.size() - 1; index >= 0; index--) {
            String value = normalize(output.get(index));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private List<String> readOutputLines(Path logFile) {
        if (!Files.exists(logFile)) {
            return List.of();
        }

        try (Stream<String> lines = Files.lines(logFile, StandardCharsets.UTF_8)) {
            return lines.limit(maxOutputLines).toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
