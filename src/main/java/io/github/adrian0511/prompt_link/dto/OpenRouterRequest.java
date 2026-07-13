package io.github.adrian0511.prompt_link.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * El cuerpo que se envía a {@code POST /chat/completions}.
 *
 * <p>Los campos nulos se omiten al serializar: así, si no configuras {@code temperature}, no se
 * manda y cada modelo aplica su propio valor por defecto, que es lo que se espera.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenRouterRequest {

    private String model;
    private List<Message> messages;

    /** La API espera snake_case; el resto de campos coinciden con el nombre Java. */
    @JsonProperty("max_tokens")
    private int maxTokens;

    private Double temperature;

    /** Si es {@code true}, la respuesta llega como eventos SSE en vez de como un cuerpo único. */
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
