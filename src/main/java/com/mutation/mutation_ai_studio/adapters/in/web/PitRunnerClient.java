package com.mutation.mutation_ai_studio.adapters.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@Service
public class PitRunnerClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final long timeoutMinutes;

    public PitRunnerClient(
            ObjectMapper objectMapper,
            @Value("${workspace.pit-runner.base-url:http://127.0.0.1:17845}") String baseUrl,
            @Value("${workspace.pit-runner.timeout-minutes:25}") long timeoutMinutes) {
        this.objectMapper = objectMapper;
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.timeoutMinutes = timeoutMinutes;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    CommandExecutionResult executePit(Path repositoryRoot, String mavenPath, List<String> targetClasses) throws Exception {
        return execute(repositoryRoot, "/run-pit", new PitRunnerRequest(
                repositoryRoot.toString(),
                mavenPath,
                targetClasses == null ? List.of() : List.copyOf(targetClasses),
                List.of()));
    }

    CommandExecutionResult executeTests(Path repositoryRoot, String mavenPath) throws Exception {
        return execute(repositoryRoot, "/run-tests", new PitRunnerRequest(
                repositoryRoot.toString(),
                mavenPath,
                List.of(),
                List.of()));
    }

    private CommandExecutionResult execute(Path repositoryRoot, String route, PitRunnerRequest payload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + route))
                .timeout(Duration.ofMinutes(timeoutMinutes))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("Runner local PIT respondeu HTTP " + response.statusCode());
        }

        PitRunnerResponse runnerResponse = objectMapper.readValue(response.body(), PitRunnerResponse.class);
        return new CommandExecutionResult(
                runnerResponse.command() == null ? List.of() : List.copyOf(runnerResponse.command()),
                runnerResponse.exitCode(),
                runnerResponse.timedOut(),
                runnerResponse.durationMs(),
                runnerResponse.outputLines() == null ? List.of() : List.copyOf(runnerResponse.outputLines()));
    }

    String baseUrl() {
        return baseUrl;
    }

    private String normalizeBaseUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.endsWith("/")) {
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private record PitRunnerRequest(
            String projectPath,
            String mavenPath,
            List<String> targetClasses,
            List<String> targetTests) {
    }

    private record PitRunnerResponse(
            List<String> command,
            int exitCode,
            boolean timedOut,
            long durationMs,
            List<String> outputLines,
            String reportPath) {
    }
}
