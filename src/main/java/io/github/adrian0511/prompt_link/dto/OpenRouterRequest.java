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

    /** Fallbacks OpenRouter tries in order when {@code model} fails. */
    private List<String> models;

    private List<Message> messages;

    /** The API expects snake_case here; every other field matches its Java name. */
    @JsonProperty("max_tokens")
    private int maxTokens;

    private Double temperature;

    /** When {@code true}, the answer arrives as SSE events instead of as a single body. */
    private Boolean stream;

    private Reasoning reasoning;

    /** The {@code reasoning} block. Only the configured fields are sent. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Reasoning {

        private Boolean enabled;
        private String effort;

        @JsonProperty("max_tokens")
        private Integer maxTokens;

        private Boolean exclude;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public String getEffort() {
            return effort;
        }

        public void setEffort(String effort) {
            this.effort = effort;
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }

        public Boolean getExclude() {
            return exclude;
        }

        public void setExclude(Boolean exclude) {
            this.exclude = exclude;
        }
    }

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

    public List<String> getModels() {
        return models;
    }

    public void setModels(List<String> models) {
        this.models = models;
    }

    public Reasoning getReasoning() {
        return reasoning;
    }

    public void setReasoning(Reasoning reasoning) {
        this.reasoning = reasoning;
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
