package com.mutation.mutation_ai_studio.adapters.in.web;

import com.mutation.mutation_ai_studio.adapters.in.web.dto.AiTestClassResult;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.AiTestRunStatusResponse;

import java.util.List;

final class AiTestRunState {
    private final String runId;
    private final String projectId;
    private final int totalClasses;

    private String status;
    private String message;
    private List<AiTestClassResult> results;

    private AiTestRunState(String runId, String projectId, int totalClasses) {
        this.runId = runId;
        this.projectId = projectId;
        this.totalClasses = totalClasses;
        this.status = "queued";
        this.message = "Aguardando geracao com IA...";
        this.results = List.of();
    }

    static AiTestRunState queued(String runId, String projectId, int totalClasses) {
        return new AiTestRunState(runId, projectId, totalClasses);
    }

    synchronized void markRunning(String message) {
        this.status = "running";
        this.message = message;
    }

    synchronized void markCompleted(String message, List<AiTestClassResult> results) {
        this.status = "completed";
        this.message = message;
        this.results = List.copyOf(results);
    }

    synchronized void markFailed(String message) {
        this.status = "failed";
        this.message = message;
    }

    synchronized AiTestRunStatusResponse toResponse() {
        long passed = results.stream().filter(AiTestClassResult::passed).count();
        return new AiTestRunStatusResponse(
                runId,
                projectId,
                status,
                message,
                totalClasses,
                (int) passed,
                (int) (results.size() - passed),
                results
        );
    }
}
