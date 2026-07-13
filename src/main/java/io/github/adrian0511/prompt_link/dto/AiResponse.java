package io.github.adrian0511.prompt_link.dto;

/**
 * La respuesta del modelo, ya extraída de la estructura de OpenRouter. Aísla al llamante del
 * formato de la API: si esta cambia, {@link #getContent()} sigue significando lo mismo.
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
