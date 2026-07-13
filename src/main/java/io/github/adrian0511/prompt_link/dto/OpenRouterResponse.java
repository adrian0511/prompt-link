package io.github.adrian0511.prompt_link.dto;

import java.util.List;

/**
 * El cuerpo que devuelve {@code POST /chat/completions}.
 *
 * <p>La API puede devolver varias alternativas de respuesta; la librería usa siempre la primera.
 * Ojo: la lista puede llegar vacía incluso con un 200, así que no se puede indexar a ciegas.
 */
public class OpenRouterResponse {

    private List<Choice> choices;

    /** Una de las alternativas de respuesta generadas por el modelo. */
    public static class Choice {
        private Message message;

        public Message getMessage() {
            return message;
        }

        public void setMessage(Message message) {
            this.message = message;
        }

    }

    public List<Choice> getChoices() {
        return choices;
    }

    public void setChoices(List<Choice> choices) {
        this.choices = choices;
    }

}
