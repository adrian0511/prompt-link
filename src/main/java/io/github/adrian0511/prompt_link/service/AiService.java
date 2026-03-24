package io.github.adrian0511.prompt_link.service;

import java.util.List;

import org.springframework.stereotype.Service;

import io.github.adrian0511.prompt_link.client.AiClient;
import io.github.adrian0511.prompt_link.config.AiProperties;
import io.github.adrian0511.prompt_link.dto.AiResponse;
import io.github.adrian0511.prompt_link.dto.Message;
import io.github.adrian0511.prompt_link.dto.OpenRouterRequest;
import io.github.adrian0511.prompt_link.dto.OpenRouterResponse;

@Service
public class AiService {

    private final AiClient aiClient;
    private final AiProperties properties;

    public AiService(AiClient aiClient, AiProperties properties) {
        this.aiClient = aiClient;
        this.properties = properties;
    }

    public AiResponse generate(String prompt) {
        OpenRouterRequest request = new OpenRouterRequest();
        request.setModel(properties.getModel());
        request.setMessages(List.of(new Message("user", prompt)));
        request.setMaxTokens(properties.getMaxTokens());

        OpenRouterResponse response = aiClient.chatCompletion(request);
        String content = response.getChoices().get(0).getMessage().getContent();

        return new AiResponse(content);
    }

}
