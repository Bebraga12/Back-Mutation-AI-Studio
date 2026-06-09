package com.mutation.mutation_ai_studio.adapters.out.process;

import com.mutation.mutation_ai_studio.application.port.out.AiTestGeneratorPort;
import com.mutation.mutation_ai_studio.domain.model.ClassTestPrompt;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class OllamaAiTestGeneratorAdapter implements AiTestGeneratorPort {

    private final ChatClient chatClient;
    private final OllamaModelConfig modelConfig;

    public OllamaAiTestGeneratorAdapter(
            ObjectProvider<ChatClient.Builder> chatClientBuilderProvider,
            OllamaModelConfig modelConfig) {
        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        if (builder == null) {
            throw new IllegalStateException("ChatClient.Builder não está disponível. Verifique a configuração do Spring AI/Ollama.");
        }
        this.chatClient = builder.build();
        this.modelConfig = modelConfig;
    }

    @Override
    public String generateTestCode(ClassTestPrompt prompt) {
        String response = chatClient.prompt()
                .options(OllamaChatOptions.builder().model(modelConfig.getModel()).build())
                .user(prompt.prompt())
                .call()
                .content();

        if (response == null || response.isBlank()) {
            throw new IllegalStateException("Ollama retornou uma resposta vazia para o prompt de geração de teste.");
        }

        return response;
    }
}
