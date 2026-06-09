package com.mutation.mutation_ai_studio.adapters.in.web.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.ApiProject;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.CreateProjectRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectCatalogServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPersistCreatedProjectsInJsonFile() {
        Path repositoryPath = createMavenProject("customer-api");
        ProjectCatalogService service = newService();

        ApiProject created = service.createProject(new CreateProjectRequest(
                " Customer API ",
                " " + repositoryPath + " ",
                " /usr/bin/mvn "));

        assertEquals("customer-api", created.id());
        assertEquals("Customer API", created.name());
        assertEquals(repositoryPath.toString(), created.repositoryPath());
        assertEquals("/usr/bin/mvn", created.mavenPath());

        ProjectCatalogService reloadedService = newService();
        List<ApiProject> persistedProjects = reloadedService.listProjects();

        assertEquals(1, persistedProjects.size());
        assertEquals(created, persistedProjects.get(0));
    }

    @Test
    void shouldGenerateUniqueIdsAndDeleteProjects() {
        ProjectCatalogService service = newService();
        Path firstRepository = createMavenProject("repo-a");
        Path secondRepository = createMavenProject("repo-b");

        ApiProject first = service.createProject(new CreateProjectRequest("Billing Core", firstRepository.toString(), ""));
        ApiProject second = service.createProject(new CreateProjectRequest("Billing Core", secondRepository.toString(), ""));

        assertEquals("billing-core", first.id());
        assertEquals("billing-core-1", second.id());
        assertTrue(service.deleteProject(first.id()));
        assertFalse(service.findProject(first.id()).isPresent());
    }

    @Test
    void shouldInstallPitestPluginOnlyOnce() throws Exception {
        Path repositoryPath = createMavenProject("billing");
        ProjectCatalogService service = newService();

        service.createProject(new CreateProjectRequest("Billing", repositoryPath.toString(), ""));
        service.createProject(new CreateProjectRequest("Billing Again", repositoryPath.toString(), ""));

        String pomContent = Files.readString(repositoryPath.resolve("pom.xml"));
        assertEquals(1, countOccurrences(pomContent, "<artifactId>pitest-maven</artifactId>"));
        assertEquals(1, countOccurrences(pomContent, "<artifactId>pitest-junit5-plugin</artifactId>"));
        assertTrue(pomContent.contains("<version>1.2.3</version>"));
    }

    @Test
    void shouldUpgradeLegacyPitestJunit5PluginVersion() throws Exception {
        Path repositoryPath = createMavenProjectWithLegacyPitestPlugin("legacy-billing");
        ProjectCatalogService service = newService();

        service.ensurePitestPluginInstalled(repositoryPath.toString());

        String pomContent = Files.readString(repositoryPath.resolve("pom.xml"));
        assertTrue(pomContent.contains("<version>1.2.3</version>"));
        assertFalse(pomContent.contains("<version>1.2.1</version>"));
    }

    @Test
    void shouldRejectProjectWithoutPomXml() {
        ProjectCatalogService service = newService();
        Path repositoryPath = tempDir.resolve("sem-pom");

        assertThrows(IllegalArgumentException.class,
                () -> service.createProject(new CreateProjectRequest("Sem Pom", repositoryPath.toString(), "")));
    }

    private ProjectCatalogService newService() {
        Path storageFile = tempDir.resolve("data/projects/projects.json");
        ProjectCatalogFileRepository repository = new ProjectCatalogFileRepository(
                new ObjectMapper(),
                storageFile.toString());
        return new ProjectCatalogService(repository, new PitestPomPluginInstaller());
    }

    private Path createMavenProject(String directoryName) {
        try {
            Path repositoryPath = tempDir.resolve(directoryName);
            Files.createDirectories(repositoryPath);
            Files.writeString(repositoryPath.resolve("pom.xml"), """
                    <project>
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>sample</artifactId>
                        <version>1.0.0</version>
                    </project>
                    """);
            return repositoryPath;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Path createMavenProjectWithLegacyPitestPlugin(String directoryName) {
        try {
            Path repositoryPath = tempDir.resolve(directoryName);
            Files.createDirectories(repositoryPath);
            Files.writeString(repositoryPath.resolve("pom.xml"), """
                    <project>
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>sample</artifactId>
                        <version>1.0.0</version>
                        <build>
                            <plugins>
                                <plugin>
                                    <groupId>org.pitest</groupId>
                                    <artifactId>pitest-maven</artifactId>
                                    <version>1.17.3</version>
                                    <dependencies>
                                        <dependency>
                                            <groupId>org.pitest</groupId>
                                            <artifactId>pitest-junit5-plugin</artifactId>
                                            <version>1.2.1</version>
                                        </dependency>
                                    </dependencies>
                                </plugin>
                            </plugins>
                        </build>
                    </project>
                    """);
            return repositoryPath;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private int countOccurrences(String text, String fragment) {
        return text.split(java.util.regex.Pattern.quote(fragment), -1).length - 1;
    }
}
