package io.github.adrian0511.prompt_link.service;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.adrian0511.prompt_link.config.AiProperties;
import io.github.adrian0511.prompt_link.dto.AiResponse;
import io.github.adrian0511.prompt_link.dto.Message;
import io.github.adrian0511.prompt_link.dto.OpenRouterRequest;
import io.github.adrian0511.prompt_link.dto.OpenRouterResponse;
import io.github.adrian0511.prompt_link.dto.OpenRouterStreamChunk;
import io.github.adrian0511.prompt_link.exceptions.AiClientException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Variante reactiva de {@link AiService}, para aplicaciones WebFlux.
 *
 * <p>Su razón de ser es {@link #stream(String)}: devolver los tokens según el modelo los genera, en
 * lugar de esperar a que termine la respuesta entera. Es lo que hace que un chat se sienta vivo, y
 * es imposible con el cliente bloqueante. {@link #generate(String)} viene de propina, para no
 * obligar a mezclar los dos servicios en una aplicación reactiva.
 *
 * <p>Solo se registra si WebFlux está en el classpath. Los errores son exactamente los mismos que
 * los del servicio bloqueante — {@link AiClientException} con el mismo significado en su
 * {@code statusCode} —, de modo que cambiar de uno a otro no obliga a reescribir el manejo de
 * errores.
 *
 * <pre>{@code
 * Flux<String> tokens = reactiveAiService.stream("Cuéntame un cuento");
 * Mono<AiResponse> respuesta = reactiveAiService.generate("¿Qué tal?");
 * }</pre>
 */
public class ReactiveAiService {

    /** Evento con el que OpenRouter cierra el stream. No es JSON: hay que filtrarlo antes de parsear. */
    private static final String FIN_DEL_STREAM = "[DONE]";

    private final WebClient webClient;
    private final AiProperties properties;
    private final ObjectMapper objectMapper;

    public ReactiveAiService(WebClient webClient, AiProperties properties, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Envía un único mensaje de usuario y emite la respuesta completa.
     *
     * @param prompt la pregunta o instrucción para el modelo
     * @return la respuesta del modelo, o un error {@link AiClientException}
     */
    public Mono<AiResponse> generate(String prompt) {
        return generate(List.of(Message.user(prompt)));
    }

    /**
     * Envía un prompt de sistema y uno de usuario, y emite la respuesta completa.
     *
     * @param systemPrompt las instrucciones de comportamiento para el modelo
     * @param userPrompt la pregunta o instrucción del usuario
     * @return la respuesta del modelo, o un error {@link AiClientException}
     */
    public Mono<AiResponse> generate(String systemPrompt, String userPrompt) {
        return generate(List.of(Message.system(systemPrompt), Message.user(userPrompt)));
    }

    /**
     * Envía una conversación completa y emite la respuesta del modelo.
     *
     * @param messages la conversación en orden cronológico; no puede estar vacía
     * @return la respuesta del modelo, o un error {@link AiClientException}
     */
    public Mono<AiResponse> generate(List<Message> messages) {
        return Mono.defer(() -> {
            validar(messages);

            return peticion(messages, false)
                    .retrieve()
                    .onStatus(status -> status.isError(), this::traducirError)
                    .bodyToMono(OpenRouterResponse.class)
                    .map(this::extraerContenido)
                    .onErrorMap(noEsDeLaLibreria(), this::comoErrorDeRed);
        });
    }

    /**
     * Envía un único mensaje de usuario y emite el texto por trozos, según el modelo lo genera.
     *
     * @param prompt la pregunta o instrucción para el modelo
     * @return los fragmentos de texto en orden, o un error {@link AiClientException}
     */
    public Flux<String> stream(String prompt) {
        return stream(List.of(Message.user(prompt)));
    }

    /**
     * Envía un prompt de sistema y uno de usuario, y emite el texto por trozos.
     *
     * @param systemPrompt las instrucciones de comportamiento para el modelo
     * @param userPrompt la pregunta o instrucción del usuario
     * @return los fragmentos de texto en orden, o un error {@link AiClientException}
     */
    public Flux<String> stream(String systemPrompt, String userPrompt) {
        return stream(List.of(Message.system(systemPrompt), Message.user(userPrompt)));
    }

    /**
     * Envía una conversación completa y emite el texto por trozos, según el modelo lo genera.
     *
     * <p>Los fragmentos no tienen un tamaño garantizado: pueden ser una palabra, una sílaba o un
     * signo de puntuación. Concaténalos para reconstruir la respuesta.
     *
     * @param messages la conversación en orden cronológico; no puede estar vacía
     * @return los fragmentos de texto en orden, o un error {@link AiClientException}
     */
    public Flux<String> stream(List<Message> messages) {
        return Flux.defer(() -> {
            validar(messages);

            return peticion(messages, true)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .retrieve()
                    .onStatus(status -> status.isError(), this::traducirError)
                    .bodyToFlux(String.class)
                    .takeUntil(FIN_DEL_STREAM::equals)
                    .filter(evento -> !FIN_DEL_STREAM.equals(evento))
                    .map(this::extraerFragmento)
                    .filter(fragmento -> !fragmento.isEmpty())
                    .onErrorMap(noEsDeLaLibreria(), this::comoErrorDeRed);
        });
    }

    private WebClient.RequestHeadersSpec<?> peticion(List<Message> messages, boolean streaming) {
        OpenRouterRequest cuerpo = new OpenRouterRequest();
        cuerpo.setModel(properties.getModel());
        cuerpo.setMessages(List.copyOf(messages));
        cuerpo.setMaxTokens(properties.getMaxTokens());
        cuerpo.setTemperature(properties.getTemperature());
        cuerpo.setStream(streaming ? Boolean.TRUE : null);

        return webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(cuerpo);
    }

    /**
     * Traduce una respuesta de error al mismo {@link AiClientException} que produce el cliente
     * bloqueante, conservando el status y el cuerpo.
     */
    private Mono<Throwable> traducirError(ClientResponse response) {
        int status = response.statusCode().value();
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(cuerpo -> new AiClientException(
                        "Error llamando a la API de IA: " + status, status, cuerpo));
    }

    private AiResponse extraerContenido(OpenRouterResponse response) {
        if (response.getChoices() == null || response.getChoices().isEmpty()) {
            throw new AiClientException(
                    "La API de IA devolvió una respuesta sin choices",
                    AiClientException.INVALID_RESPONSE,
                    null);
        }

        Message message = response.getChoices().get(0).getMessage();
        if (message == null || message.getContent() == null) {
            throw new AiClientException(
                    "La API de IA devolvió un choice sin contenido",
                    AiClientException.INVALID_RESPONSE,
                    null);
        }

        return new AiResponse(message.getContent());
    }

    /** Devuelve el texto del fragmento, o cadena vacía si el evento no lleva contenido. */
    private String extraerFragmento(String evento) {
        try {
            OpenRouterStreamChunk chunk = objectMapper.readValue(evento, OpenRouterStreamChunk.class);
            if (chunk.getChoices() == null || chunk.getChoices().isEmpty()) {
                return "";
            }

            OpenRouterStreamChunk.Delta delta = chunk.getChoices().get(0).getDelta();
            return delta == null || delta.getContent() == null ? "" : delta.getContent();
        } catch (Exception e) {
            throw new AiClientException(
                    "No se pudo interpretar un fragmento del stream de la API de IA",
                    AiClientException.INVALID_RESPONSE,
                    evento,
                    e);
        }
    }

    private void validar(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("La conversación debe tener al menos un mensaje");
        }
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new AiClientException(
                    "La propiedad ai.api-key no está configurada",
                    AiClientException.CONFIGURATION_ERROR,
                    null);
        }
    }

    /** Todo lo que no haya tipado ya la librería es un fallo de transporte. */
    private java.util.function.Predicate<Throwable> noEsDeLaLibreria() {
        return e -> !(e instanceof AiClientException);
    }

    private AiClientException comoErrorDeRed(Throwable e) {
        return new AiClientException(
                "Error de comunicación con la API de IA: " + e.getMessage(),
                AiClientException.NETWORK_ERROR,
                null,
                e);
    }

}
