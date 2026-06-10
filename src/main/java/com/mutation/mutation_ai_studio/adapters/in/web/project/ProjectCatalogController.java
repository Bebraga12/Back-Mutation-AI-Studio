package com.mutation.mutation_ai_studio.adapters.in.web.project;

import com.mutation.mutation_ai_studio.adapters.in.web.InMemoryWorkspaceApiService;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.ApiItemsResponse;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.ApiProject;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.CreateProjectRequest;
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
@RequestMapping("/api/projects")
@CrossOrigin(origins = {"http://localhost:4200", "http://127.0.0.1:4200"})
public class ProjectCatalogController {

    private final ProjectCatalogService projectCatalogService;
    private final InMemoryWorkspaceApiService workspaceService;

    public ProjectCatalogController(
            ProjectCatalogService projectCatalogService,
            InMemoryWorkspaceApiService workspaceService) {
        this.projectCatalogService = projectCatalogService;
        this.workspaceService = workspaceService;
    }

    @GetMapping
    public ApiItemsResponse<ApiProject> listProjects() {
        return new ApiItemsResponse<>(projectCatalogService.listProjects());
    }

    @GetMapping("/{projectId}")
    public ApiProject getProject(@PathVariable String projectId) {
        return projectCatalogService.findProject(projectId)
                .orElseThrow(() -> notFound(projectId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiProject createProject(@Valid @RequestBody CreateProjectRequest request) {
        try {
            ApiProject createdProject = projectCatalogService.createProject(request);
            workspaceService.registerProject(createdProject);
            return createdProject;
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProject(@PathVariable String projectId) {
        boolean removed = projectCatalogService.deleteProject(projectId);
        if (!removed) {
            throw notFound(projectId);
        }

        workspaceService.unregisterProject(projectId);
    }

    private ResponseStatusException notFound(String projectId) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Projeto nao encontrado: " + projectId);
    }
}
