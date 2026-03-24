package io.github.adrian0511.prompt_link.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public class OpenRouterRequest {

    private String model;
    private List<Message> messages;

    @JsonProperty("max_tokens")
    private int maxTokens;

    public OpenRouterRequest() {
    }

    public OpenRouterRequest(String model, List<Message> messages, int maxTokens) {
        this.model = model;
        this.messages = messages;
        this.maxTokens = maxTokens;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

}
