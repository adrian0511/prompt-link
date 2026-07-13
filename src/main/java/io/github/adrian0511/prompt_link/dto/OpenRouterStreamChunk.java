package io.github.adrian0511.prompt_link.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Un fragmento de una respuesta en streaming.
 *
 * <p>Cuando se pide {@code stream: true}, OpenRouter no devuelve un cuerpo único sino una sucesión
 * de eventos SSE, cada uno con el trocito de texto recién generado dentro de {@code delta.content}.
 * El último evento es un {@code [DONE]} literal, que no es JSON y por eso se filtra antes de
 * deserializar.
 *
 * <p>Un {@code delta} puede venir sin contenido (por ejemplo, el primer evento suele traer solo el
 * rol), así que {@link Delta#getContent()} puede ser {@code null}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenRouterStreamChunk {

    private List<Choice> choices;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Choice {

        private Delta delta;

        public Delta getDelta() {
            return delta;
        }

        public void setDelta(Delta delta) {
            this.delta = delta;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Delta {

        private String content;

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }

    public List<Choice> getChoices() {
        return choices;
    }

    public void setChoices(List<Choice> choices) {
        this.choices = choices;
    }

}
