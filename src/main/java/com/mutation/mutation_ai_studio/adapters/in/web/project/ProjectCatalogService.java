package com.mutation.mutation_ai_studio.adapters.in.web.project;

import com.mutation.mutation_ai_studio.adapters.in.web.dto.ApiProject;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.CreateProjectRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class ProjectCatalogService {

    private final ProjectCatalogFileRepository repository;
    private final PitestPomPluginInstaller pitestPomPluginInstaller;

    public ProjectCatalogService(
            ProjectCatalogFileRepository repository,
            PitestPomPluginInstaller pitestPomPluginInstaller) {
        this.repository = repository;
        this.pitestPomPluginInstaller = pitestPomPluginInstaller;
    }

    public List<ApiProject> listProjects() {
        return repository.loadProjects().stream()
                .sorted(Comparator.comparing(ApiProject::name))
                .toList();
    }

    public Optional<ApiProject> findProject(String projectId) {
        String normalizedProjectId = normalize(projectId);
        return repository.loadProjects().stream()
                .filter(project -> project.id().equals(normalizedProjectId))
                .findFirst();
    }

    public synchronized ApiProject createProject(CreateProjectRequest request) {
        List<ApiProject> projects = new ArrayList<>(repository.loadProjects());
        String projectName = normalize(request.name());
        String repositoryPath = normalize(request.repositoryPath());
        String projectId = buildUniqueProjectId(projectName, projects);

        ensurePitestPluginInstalled(repositoryPath);

        ApiProject project = new ApiProject(
                projectId,
                projectName,
                repositoryPath,
                normalize(request.mavenPath()),
                0,
                "Projeto novo");

        projects.add(project);
        repository.saveProjects(projects);
        return project;
    }

    public synchronized void ensurePitestPluginInstalled(String repositoryPath) {
        pitestPomPluginInstaller.ensurePluginInstalled(normalize(repositoryPath));
    }

    public synchronized boolean deleteProject(String projectId) {
        List<ApiProject> projects = new ArrayList<>(repository.loadProjects());
        boolean removed = projects.removeIf(project -> project.id().equals(normalize(projectId)));
        if (removed) {
            repository.saveProjects(projects);
        }
        return removed;
    }

    public synchronized ApiProject saveProject(ApiProject project) {
        List<ApiProject> projects = new ArrayList<>(repository.loadProjects());
        projects.removeIf(current -> current.id().equals(project.id()));
        projects.add(project);
        repository.saveProjects(projects);
        return project;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String buildUniqueProjectId(String projectName, List<ApiProject> projects) {
        String slug = projectName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");

        String base = slug.isBlank() ? "project" : slug;
        String current = base;
        int counter = 1;

        while (containsProjectId(projects, current)) {
            current = base + "-" + counter;
            counter++;
        }

        return current;
    }

    private boolean containsProjectId(List<ApiProject> projects, String projectId) {
        return projects.stream().anyMatch(project -> project.id().equals(projectId));
    }
}
