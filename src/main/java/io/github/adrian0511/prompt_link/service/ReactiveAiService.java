package io.github.adrian0511.prompt_link.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.adrian0511.prompt_link.config.AiProperties;
import io.github.adrian0511.prompt_link.dto.AiResponse;
import io.github.adrian0511.prompt_link.dto.Message;
import io.github.adrian0511.prompt_link.dto.OpenRouterRequest;
import io.github.adrian0511.prompt_link.dto.OpenRouterResponse;
import io.github.adrian0511.prompt_link.exceptions.AiClientException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

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
                    .onErrorMap(noEsDeLaLibreria(), this::comoErrorDeRed)
                    // Reintentar aquí es seguro: no se ha entregado nada al llamante todavía.
                    .retryWhen(politicaDeReintentos(() -> false));
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

            // Reintentar un stream a medias duplicaría texto en la pantalla del usuario: si ya se
            // emitió un fragmento, la respuesta empezaría de cero y él vería la frase dos veces.
            // Solo es seguro reintentar mientras no se haya entregado nada.
            AtomicBoolean yaSeEmitio = new AtomicBoolean();

            return peticion(messages, true)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .retrieve()
                    .onStatus(status -> status.isError(), this::traducirError)
                    .bodyToFlux(String.class)
                    .takeUntil(FIN_DEL_STREAM::equals)
                    .filter(evento -> !FIN_DEL_STREAM.equals(evento))
                    .map(this::extraerFragmento)
                    .filter(fragmento -> !fragmento.isEmpty())
                    .doOnNext(fragmento -> yaSeEmitio.set(true))
                    .onErrorMap(noEsDeLaLibreria(), this::comoErrorDeRed)
                    .retryWhen(politicaDeReintentos(yaSeEmitio::get));
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

    /**
     * Política de reintentos equivalente a la del cliente bloqueante, pero expresada en Reactor:
     * el {@code Retryer} de Feign es un bean suyo y no tiene ningún efecto por este camino.
     *
     * @param yaSeEmitio si ya se entregó algún fragmento al llamante, en cuyo caso reintentar
     *     duplicaría texto y no se hace
     */
    private Retry politicaDeReintentos(BooleanSupplier yaSeEmitio) {
        AiProperties.Retry config = properties.getRetry();

        // Sin onRetryExhaustedThrow, Reactor envuelve el fallo en una RetryExhaustedException y el
        // llamante perdería la AiClientException con su status y su cuerpo. Hace falta en las dos
        // ramas: incluso sin reintentos, Retry.max(0) envuelve el error del primer intento.
        if (!config.isEnabled()) {
            return Retry.max(0).onRetryExhaustedThrow((spec, signal) -> signal.failure());
        }

        return Retry.backoff(config.getMaxAttempts() - 1L, config.getPeriod())
                .maxBackoff(config.getMaxPeriod())
                .filter(e -> esReintentable(e) && !yaSeEmitio.getAsBoolean())
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    /**
     * Los mismos criterios que en el cliente bloqueante — rate limits, errores del servidor y red —
     * más los fallos a mitad de stream.
     *
     * <p>Un fallo de stream es transitorio por naturaleza (la petición ya fue aceptada con un 200;
     * lo que se rompió fue el proveedor o el transporte), así que reintentarlo tiene sentido. Que
     * sea <em>seguro</em> lo garantiza el guardia de {@link #stream(List)}: si ya se entregó algún
     * token, no se reintenta, porque el usuario vería el texto duplicado.
     */
    private static boolean esReintentable(Throwable e) {
        if (!(e instanceof AiClientException error)) {
            return false;
        }
        int status = error.getStatusCode();
        return status == 429
                || status >= 500
                || status == AiClientException.NETWORK_ERROR
                || status == AiClientException.STREAM_ERROR;
    }

    /**
     * Devuelve el texto del fragmento, o cadena vacía si el evento no lleva contenido.
     *
     * <p>Ojo con el caso peligroso: OpenRouter puede mandar un error <em>dentro</em> del stream,
     * después de haber respondido 200 y de haber emitido tokens, con el fallo en un campo
     * {@code error} de primer nivel. Ese evento trae además un {@code choices} con el contenido
     * vacío, así que sin esta comprobación se parsearía sin protestar, el fragmento vacío se
     * filtraría y el stream terminaría <em>con normalidad</em>: el usuario vería su respuesta
     * cortada a media frase y la aplicación no se enteraría de nada.
     */
    private String extraerFragmento(String evento) {
        JsonNode nodo;
        try {
            nodo = objectMapper.readTree(evento);
        } catch (Exception e) {
            throw new AiClientException(
                    "No se pudo interpretar un fragmento del stream de la API de IA",
                    AiClientException.INVALID_RESPONSE,
                    evento,
                    e);
        }

        JsonNode error = nodo.get("error");
        if (error != null && !error.isNull()) {
            throw new AiClientException(
                    "La API de IA falló a mitad del stream: "
                            + error.path("message").asText("sin detalle"),
                    statusDelError(error),
                    evento);
        }

        JsonNode delta = nodo.path("choices").path(0).path("delta").path("content");
        return delta.isTextual() ? delta.asText() : "";
    }

    /**
     * El código del error puede venir como número (429) o como texto ("server_error").
     *
     * <p>Cuando es numérico se respeta, con su significado de siempre: un 402 a mitad de stream
     * sigue sin reintentarse. Cuando es texto no hay forma de saber qué clase de fallo es, y se
     * clasifica como {@link AiClientException#STREAM_ERROR}, que sí es reintentable. Sin esa
     * distinción, el mismo fallo del proveedor se reintentaría o no según el tipo JSON que
     * OpenRouter le pusiera al código, que es una diferencia absurda.
     */
    private static int statusDelError(JsonNode error) {
        JsonNode codigo = error.path("code");
        return codigo.isInt() ? codigo.asInt() : AiClientException.STREAM_ERROR;
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
