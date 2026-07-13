package io.github.adrian0511.prompt_link.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

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
 * The reactive counterpart of {@link AiService}, for WebFlux applications.
 *
 * <p>Its reason to exist is {@link #stream(String)}: emitting tokens as the model generates them,
 * instead of waiting for the whole answer to be finished. That is what makes a chat feel alive, and
 * it is impossible with the blocking client. {@link #generate(String)} comes along for the ride, so
 * that a reactive application does not have to mix both services.
 *
 * <p>It is only registered when WebFlux is on the classpath. Errors are exactly the same as in the
 * blocking service — an {@link AiClientException} whose {@code statusCode} means the same thing — so
 * moving from one to the other does not force you to rewrite your error handling.
 *
 * <pre>{@code
 * Flux<String> tokens = reactiveAiService.stream("Tell me a story");
 * Mono<AiResponse> answer = reactiveAiService.generate("How are you?");
 * }</pre>
 */
public class ReactiveAiService {

    /** The event OpenRouter closes the stream with. It is not JSON, so it is filtered before parsing. */
    private static final String END_OF_STREAM = "[DONE]";

    private static final int TOO_MANY_REQUESTS = 429;

    private final WebClient webClient;
    private final AiProperties properties;
    private final ObjectMapper objectMapper;

    public ReactiveAiService(WebClient webClient, AiProperties properties, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Sends a single user message and emits the complete answer.
     *
     * @param prompt the question or instruction for the model
     * @return the model's answer, or an {@link AiClientException} error signal
     */
    public Mono<AiResponse> generate(String prompt) {
        return generate(List.of(Message.user(prompt)));
    }

    /**
     * Sends a user message preceded by a system prompt, and emits the complete answer.
     *
     * @param systemPrompt the behaviour instructions for the model
     * @param userPrompt the user's question or instruction
     * @return the model's answer, or an {@link AiClientException} error signal
     */
    public Mono<AiResponse> generate(String systemPrompt, String userPrompt) {
        return generate(List.of(Message.system(systemPrompt), Message.user(userPrompt)));
    }

    /**
     * Sends a whole conversation and emits the model's answer.
     *
     * @param messages the conversation in chronological order; must not be empty
     * @return the model's answer, or an {@link AiClientException} error signal
     */
    public Mono<AiResponse> generate(List<Message> messages) {
        return Mono.defer(() -> {
            validate(messages);

            return request(messages, false)
                    .retrieve()
                    .onStatus(status -> status.isError(), this::toTypedError)
                    .bodyToMono(OpenRouterResponse.class)
                    .map(this::extractContent)
                    .onErrorMap(notTypedYet(), this::asNetworkError)
                    // Retrying is safe here: nothing has been handed to the caller yet.
                    .retryWhen(retryPolicy(() -> false));
        });
    }

    /**
     * Sends a single user message and emits the text in pieces, as the model generates it.
     *
     * @param prompt the question or instruction for the model
     * @return the text fragments in order, or an {@link AiClientException} error signal
     */
    public Flux<String> stream(String prompt) {
        return stream(List.of(Message.user(prompt)));
    }

    /**
     * Sends a user message preceded by a system prompt, and emits the text in pieces.
     *
     * @param systemPrompt the behaviour instructions for the model
     * @param userPrompt the user's question or instruction
     * @return the text fragments in order, or an {@link AiClientException} error signal
     */
    public Flux<String> stream(String systemPrompt, String userPrompt) {
        return stream(List.of(Message.system(systemPrompt), Message.user(userPrompt)));
    }

    /**
     * Sends a whole conversation and emits the text in pieces, as the model generates it.
     *
     * <p>Fragments have no guaranteed size: one may be a word, a syllable or a punctuation mark.
     * Concatenate them to rebuild the answer.
     *
     * @param messages the conversation in chronological order; must not be empty
     * @return the text fragments in order, or an {@link AiClientException} error signal
     */
    public Flux<String> stream(List<Message> messages) {
        return Flux.defer(() -> {
            validate(messages);

            // Retrying a half-delivered stream would duplicate text on the user's screen: the answer
            // would start again from the beginning and they would read the same sentence twice. It is
            // only safe to retry while nothing has been handed over.
            AtomicBoolean tokenEmitted = new AtomicBoolean();

            return request(messages, true)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .retrieve()
                    .onStatus(status -> status.isError(), this::toTypedError)
                    .bodyToFlux(String.class)
                    .takeUntil(END_OF_STREAM::equals)
                    .filter(event -> !END_OF_STREAM.equals(event))
                    .map(this::extractToken)
                    .filter(token -> !token.isEmpty())
                    .doOnNext(token -> tokenEmitted.set(true))
                    .onErrorMap(notTypedYet(), this::asNetworkError)
                    .retryWhen(retryPolicy(tokenEmitted::get));
        });
    }

    private WebClient.RequestHeadersSpec<?> request(List<Message> messages, boolean streaming) {
        OpenRouterRequest body = new OpenRouterRequest();
        body.setModel(properties.getModel());
        body.setMessages(List.copyOf(messages));
        body.setMaxTokens(properties.getMaxTokens());
        body.setTemperature(properties.getTemperature());
        body.setStream(streaming ? Boolean.TRUE : null);

        return webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body);
    }

    /**
     * Turns an error response into the very same {@link AiClientException} the blocking client
     * produces, preserving the status and the body.
     */
    private Mono<Throwable> toTypedError(ClientResponse response) {
        int status = response.statusCode().value();
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> new AiClientException(
                        "Error calling the AI API: " + status, status, body));
    }

    /**
     * The retry policy of the blocking client, expressed in Reactor: Feign's {@code Retryer} is one
     * of its own beans and has no effect whatsoever on this path.
     *
     * @param tokenEmitted whether a fragment has already been handed to the caller, in which case
     *     retrying would duplicate text and is therefore not done
     */
    private Retry retryPolicy(BooleanSupplier tokenEmitted) {
        AiProperties.Retry config = properties.getRetry();

        // Without onRetryExhaustedThrow, Reactor wraps the failure in a RetryExhaustedException and
        // the caller would lose the AiClientException with its status and body. It is needed in both
        // branches: even with retries off, Retry.max(0) wraps the first attempt's error.
        if (!config.isEnabled()) {
            return Retry.max(0).onRetryExhaustedThrow((spec, signal) -> signal.failure());
        }

        return Retry.backoff(config.getMaxAttempts() - 1L, config.getPeriod())
                .maxBackoff(config.getMaxPeriod())
                .filter(e -> isRetryable(e) && !tokenEmitted.getAsBoolean())
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    /**
     * The same criteria as the blocking client — rate limits, server errors and network failures —
     * plus mid-stream failures.
     *
     * <p>A stream failure is transient by nature (the request was already accepted with a 200; what
     * broke was the provider or the transport), so retrying it makes sense. What makes it
     * <em>safe</em> is the guard in {@link #stream(List)}: once a token has been handed over, nothing
     * is retried, because the user would see the text twice.
     */
    private static boolean isRetryable(Throwable e) {
        if (!(e instanceof AiClientException error)) {
            return false;
        }
        int status = error.getStatusCode();
        return status == TOO_MANY_REQUESTS
                || status >= 500
                || status == AiClientException.NETWORK_ERROR
                || status == AiClientException.STREAM_ERROR;
    }

    private AiResponse extractContent(OpenRouterResponse response) {
        if (response.getChoices() == null || response.getChoices().isEmpty()) {
            throw new AiClientException(
                    "The AI API returned a response with no choices",
                    AiClientException.INVALID_RESPONSE,
                    null);
        }

        Message message = response.getChoices().get(0).getMessage();
        if (message == null || message.getContent() == null) {
            throw new AiClientException(
                    "The AI API returned a choice with no content",
                    AiClientException.INVALID_RESPONSE,
                    null);
        }

        return new AiResponse(message.getContent());
    }

    /**
     * Returns the text of the fragment, or an empty string when the event carries no content.
     *
     * <p>Watch out for the dangerous case: OpenRouter can send an error <em>inside</em> the stream,
     * after having answered 200 and after having emitted tokens, with the failure in a top-level
     * {@code error} field. That event also carries a {@code choices} entry with empty content, so
     * without this check it would parse just fine, the empty fragment would be filtered out and the
     * stream would end <em>normally</em>: the user would see their answer cut off mid-sentence and the
     * application would never know.
     */
    private String extractToken(String event) {
        JsonNode node;
        try {
            node = objectMapper.readTree(event);
        } catch (Exception e) {
            throw new AiClientException(
                    "Could not parse a fragment of the AI API's stream",
                    AiClientException.INVALID_RESPONSE,
                    event,
                    e);
        }

        JsonNode error = node.get("error");
        if (error != null && !error.isNull()) {
            throw new AiClientException(
                    "The AI API failed mid-stream: " + error.path("message").asText("no detail"),
                    streamErrorStatus(error),
                    event);
        }

        JsonNode content = node.path("choices").path(0).path("delta").path("content");
        return content.isTextual() ? content.asText() : "";
    }

    /**
     * The error code may arrive as a number (429) or as text ("server_error").
     *
     * <p>When it is numeric it is honoured, with its usual meaning: a 402 mid-stream is still not
     * retried. When it is text there is no way to tell what kind of failure it is, so it is
     * classified as {@link AiClientException#STREAM_ERROR}, which <em>is</em> retryable. Without that
     * distinction, the same provider failure would be retried or not depending on which JSON type
     * OpenRouter happened to give the code, which is an absurd difference.
     */
    private static int streamErrorStatus(JsonNode error) {
        JsonNode code = error.path("code");
        return code.isInt() ? code.asInt() : AiClientException.STREAM_ERROR;
    }

    private void validate(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("The conversation must have at least one message");
        }
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new AiClientException(
                    "The ai.api-key property is not configured",
                    AiClientException.CONFIGURATION_ERROR,
                    null);
        }
    }

    /** Anything this library has not already typed is a transport failure. */
    private Predicate<Throwable> notTypedYet() {
        return e -> !(e instanceof AiClientException);
    }

    private AiClientException asNetworkError(Throwable e) {
        return new AiClientException(
                "Error communicating with the AI API: " + e.getMessage(),
                AiClientException.NETWORK_ERROR,
                null,
                e);
    }

}
