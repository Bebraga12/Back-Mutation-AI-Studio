package com.mutation.mutation_ai_studio.adapters.in.web;

import com.mutation.mutation_ai_studio.adapters.in.web.dto.AiTestRunAcceptedResponse;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.AiTestRunStatusResponse;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.ApiDashboardData;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.ApiItemsResponse;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.ApiMavenDetectionResult;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.ApiProjectClass;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.MutationRunAcceptedResponse;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.MutationRunStatusResponse;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.StartAiTestRunRequest;
import com.mutation.mutation_ai_studio.adapters.in.web.dto.StartMutationRunRequest;
import com.mutation.mutation_ai_studio.adapters.out.process.OllamaModelConfig;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    private final OllamaModelConfig ollamaModelConfig;

    public WorkspaceApiController(InMemoryWorkspaceApiService workspaceService, OllamaModelConfig ollamaModelConfig) {
        this.workspaceService = workspaceService;
        this.ollamaModelConfig = ollamaModelConfig;
    }

    @GetMapping("/config/ai-model")
    public java.util.Map<String, String> getAiModel() {
        return java.util.Map.of("model", ollamaModelConfig.getModel());
    }

    @PutMapping("/config/ai-model")
    public java.util.Map<String, String> setAiModel(@RequestBody java.util.Map<String, String> body) {
        String model = body.get("model");
        try {
            ollamaModelConfig.setModel(model);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return java.util.Map.of("model", ollamaModelConfig.getModel());
    }

    @GetMapping("/projects/{projectId}/classes")
    public ApiItemsResponse<ApiProjectClass> listClasses(@PathVariable String projectId) {
        return workspaceService.findClasses(projectId)
                .map(ApiItemsResponse::new)
                .orElseThrow(() -> notFound(projectId));
    }

    @PostMapping("/projects/{projectId}/detect-maven")
    public ApiMavenDetectionResult detectMaven(@PathVariable String projectId) {
        try {
            return workspaceService.detectMaven(projectId)
                    .orElseThrow(() -> notFound(projectId));
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
        }
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

    @PostMapping("/projects/{projectId}/generate-tests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AiTestRunAcceptedResponse startAiTestRun(
            @PathVariable String projectId,
            @Valid @RequestBody StartAiTestRunRequest request) {
        try {
            return workspaceService.startAiTestRun(projectId, request);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
        }
    }

    @GetMapping("/ai-test-runs/{runId}")
    public AiTestRunStatusResponse getAiTestRunStatus(@PathVariable String runId) {
        return workspaceService.findAiTestRun(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run nao encontrado: " + runId));
    }

    @PostMapping("/utils/browse-folder")
    public java.util.Map<String, String> browseFolder() {
        final java.util.Map<String, String> response = new java.util.HashMap<>();
        System.setProperty("java.awt.headless", "false");
        try {
            javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
            chooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Selecionar Pasta do Projeto");

            int returnVal = chooser.showOpenDialog(null);
            if (returnVal == javax.swing.JFileChooser.APPROVE_OPTION) {
                response.put("path", chooser.getSelectedFile().getAbsolutePath());
            } else {
                response.put("path", "");
            }
        } catch (Exception e) {
            response.put("error", e.getMessage());
        }
        return response;
    }

    @PostMapping("/utils/browse-file")
    public java.util.Map<String, String> browseFile() {
        final java.util.Map<String, String> response = new java.util.HashMap<>();
        System.setProperty("java.awt.headless", "false");
        try {
            javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
            chooser.setFileSelectionMode(javax.swing.JFileChooser.FILES_ONLY);
            chooser.setDialogTitle("Selecionar Arquivo");

            int returnVal = chooser.showOpenDialog(null);
            if (returnVal == javax.swing.JFileChooser.APPROVE_OPTION) {
                response.put("path", chooser.getSelectedFile().getAbsolutePath());
            } else {
                response.put("path", "");
            }
        } catch (Exception e) {
            response.put("error", e.getMessage());
        }
        return response;
    }

    private ResponseStatusException notFound(String projectId) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Projeto nao encontrado: " + projectId);
    }
}
