package io.github.adrian0511.prompt_link.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The body sent to {@code POST /chat/completions}.
 *
 * <p>Null fields are omitted when serializing: if you do not configure {@code temperature} it is not
 * sent at all, and each model applies its own default, which is what callers expect.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenRouterRequest {

    private String model;
    private List<Message> messages;

    /** The API expects snake_case here; every other field matches its Java name. */
    @JsonProperty("max_tokens")
    private int maxTokens;

    private Double temperature;

    /** When {@code true}, the answer arrives as SSE events instead of as a single body. */
    private Boolean stream;

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

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Boolean getStream() {
        return stream;
    }

    public void setStream(Boolean stream) {
        this.stream = stream;
    }

}
