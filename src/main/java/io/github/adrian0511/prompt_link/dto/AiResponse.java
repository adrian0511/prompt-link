package io.github.adrian0511.prompt_link.dto;

public class AiResponse {
    private String response;

    public AiResponse() {

    }

    public AiResponse(String response) {
        this.response = response;
    }

    public String getContent() {
        return this.response;
    }
}
