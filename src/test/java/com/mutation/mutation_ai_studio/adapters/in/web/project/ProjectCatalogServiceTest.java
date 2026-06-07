package com.mutation.mutation_ai_studio.adapters.in.web.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.ApiProject;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.CreateProjectRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectCatalogServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPersistCreatedProjectsInJsonFile() {
        ProjectCatalogService service = newService();

        ApiProject created = service.createProject(new CreateProjectRequest(
                " Customer API ",
                " /workspace/customer-api ",
                " /usr/bin/mvn "));

        assertEquals("customer-api", created.id());
        assertEquals("Customer API", created.name());
        assertEquals("/workspace/customer-api", created.repositoryPath());
        assertEquals("/usr/bin/mvn", created.mavenPath());

        ProjectCatalogService reloadedService = newService();
        List<ApiProject> persistedProjects = reloadedService.listProjects();

        assertEquals(1, persistedProjects.size());
        assertEquals(created, persistedProjects.get(0));
    }

    @Test
    void shouldGenerateUniqueIdsAndDeleteProjects() {
        ProjectCatalogService service = newService();

        ApiProject first = service.createProject(new CreateProjectRequest("Billing Core", "/repo/a", ""));
        ApiProject second = service.createProject(new CreateProjectRequest("Billing Core", "/repo/b", ""));

        assertEquals("billing-core", first.id());
        assertEquals("billing-core-1", second.id());
        assertTrue(service.deleteProject(first.id()));
        assertFalse(service.findProject(first.id()).isPresent());
    }

    private ProjectCatalogService newService() {
        Path storageFile = tempDir.resolve("data/projects/projects.json");
        ProjectCatalogFileRepository repository = new ProjectCatalogFileRepository(
                new ObjectMapper(),
                storageFile.toString());
        return new ProjectCatalogService(repository);
    }
}
