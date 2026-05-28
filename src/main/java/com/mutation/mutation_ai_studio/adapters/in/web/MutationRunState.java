package com.mutation.mutation_ai_studio.adapters.in.web;

import com.mutation.mutation_ai_studio.adapters.in.web.dto.MutationRunStatusResponse;

import java.time.Instant;
import java.util.List;

final class MutationRunState {
    private final String runId;
    private final String projectId;
    private final String mavenPath;
    private final List<String> classes;

    private String status;
    private String message;
    private boolean simulated;
    private Instant startedAt;
    private Instant finishedAt;
    private Long durationMs;
    private Integer exitCode;
    private List<String> command;
    private List<String> output;

    private MutationRunState(String runId, String projectId, String mavenPath, List<String> classes) {
        this.runId = runId;
        this.projectId = projectId;
        this.mavenPath = mavenPath;
        this.classes = List.copyOf(classes);
        this.status = "queued";
        this.message = "Execucao aguardando processamento.";
        this.simulated = false;
        this.command = List.of();
        this.output = List.of();
    }

    static MutationRunState queued(String runId, String projectId, String mavenPath, List<String> classes) {
        return new MutationRunState(runId, projectId, mavenPath, classes);
    }

    synchronized void markRunning(String message, List<String> command) {
        this.status = "running";
        this.message = message;
        this.startedAt = Instant.now();
        this.command = List.copyOf(command);
    }

    synchronized void markCompleted(String message, CommandExecutionResult execution) {
        this.status = "completed";
        this.message = message;
        this.finishedAt = Instant.now();
        this.durationMs = execution.durationMs();
        this.exitCode = execution.exitCode();
        this.command = execution.command();
        this.output = execution.outputLines();
    }

    synchronized void markFailed(String message, CommandExecutionResult execution) {
        this.status = "failed";
        this.message = message;
        this.finishedAt = Instant.now();
        if (execution != null) {
            this.durationMs = execution.durationMs();
            this.exitCode = execution.exitCode();
            this.command = execution.command();
            this.output = execution.outputLines();
        }
    }

    synchronized MutationRunStatusResponse toResponse() {
        return new MutationRunStatusResponse(
                runId,
                projectId,
                status,
                message,
                simulated,
                startedAt == null ? null : startedAt.toString(),
                finishedAt == null ? null : finishedAt.toString(),
                durationMs,
                exitCode,
                command,
                output
        );
    }

    String projectId() {
        return projectId;
    }

    String mavenPath() {
        return mavenPath;
    }

    List<String> classes() {
        return classes;
    }
}
