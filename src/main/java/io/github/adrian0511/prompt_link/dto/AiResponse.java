package io.github.adrian0511.prompt_link.dto;

/**
 * The model's answer, already extracted from OpenRouter's envelope. It shields callers from the
 * API's wire format: if that changes, {@link #getContent()} still means the same thing.
 */
public class AiResponse {

    private String content;

    public AiResponse() {
    }

    public AiResponse(String content) {
        this.content = content;
    }

    public String getContent() {
        return this.content;
    }

    public void setContent(String content) {
        this.content = content;
    }

}
