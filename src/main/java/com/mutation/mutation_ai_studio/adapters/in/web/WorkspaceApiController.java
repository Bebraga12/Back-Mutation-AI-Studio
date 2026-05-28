package com.mutation.mutation_ai_studio.adapters.in.web;

import com.mutation.mutation_ai_studio.adapters.in.web.dto.ApiDashboardData;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.ApiItemsResponse;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.ApiMavenDetectionResult;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.ApiProject;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.ApiProjectClass;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.CreateProjectRequest;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.MutationRunAcceptedResponse;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.MutationRunStatusResponse;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.StartMutationRunRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = {"http://localhost:4200", "http://127.0.0.1:4200"})
public class WorkspaceApiController {

    private final InMemoryWorkspaceApiService workspaceService;

    public WorkspaceApiController(InMemoryWorkspaceApiService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @GetMapping("/projects")
    public ApiItemsResponse<ApiProject> listProjects() {
        return new ApiItemsResponse<>(workspaceService.listProjects());
    }

    @GetMapping("/projects/{projectId}")
    public ApiProject getProject(@PathVariable String projectId) {
        return workspaceService.findProject(projectId)
                .orElseThrow(() -> notFound(projectId));
    }

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiProject createProject(@Valid @RequestBody CreateProjectRequest request) {
        return workspaceService.createProject(request);
    }

    @DeleteMapping("/projects/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProject(@PathVariable String projectId) {
        boolean removed = workspaceService.deleteProject(projectId);
        if (!removed) {
            throw notFound(projectId);
        }
    }

    @GetMapping("/projects/{projectId}/classes")
    public ApiItemsResponse<ApiProjectClass> listClasses(@PathVariable String projectId) {
        return workspaceService.findClasses(projectId)
                .map(ApiItemsResponse::new)
                .orElseThrow(() -> notFound(projectId));
    }

    @PostMapping("/projects/{projectId}/detect-maven")
    public ApiMavenDetectionResult detectMaven(@PathVariable String projectId) {
        return workspaceService.detectMaven(projectId)
                .orElseThrow(() -> notFound(projectId));
    }

    @GetMapping("/projects/{projectId}/dashboard")
    public ApiDashboardData getDashboard(@PathVariable String projectId) {
        return workspaceService.findDashboard(projectId)
                .orElseThrow(() -> notFound(projectId));
    }

    @PostMapping("/mutation-runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MutationRunAcceptedResponse startMutationRun(@Valid @RequestBody StartMutationRunRequest request) {
        try {
            return workspaceService.startMutationRun(request);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
        }
    }

    @GetMapping("/mutation-runs/{runId}")
    public MutationRunStatusResponse getMutationRunStatus(@PathVariable String runId) {
        return workspaceService.findMutationRun(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Execucao nao encontrada: " + runId));
    }

    private ResponseStatusException notFound(String projectId) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Projeto nao encontrado: " + projectId);
    }
}
