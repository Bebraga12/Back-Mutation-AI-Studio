package com.mutation.mutation_ai_studio.adapters.out.process;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

@Component
public class OllamaModelConfig {

    private final AtomicReference<String> currentModel;

    public OllamaModelConfig(@Value("${spring.ai.ollama.chat.model:qwen2.5-coder:7b}") String defaultModel) {
        this.currentModel = new AtomicReference<>(defaultModel);
    }

    public String getModel() {
        return currentModel.get();
    }

    public void setModel(String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Nome do modelo nao pode ser vazio.");
        }
        currentModel.set(model.trim());
    }
}
