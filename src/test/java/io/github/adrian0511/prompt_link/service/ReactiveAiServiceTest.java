package io.github.adrian0511.prompt_link.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import io.github.adrian0511.prompt_link.config.ReactiveAiAutoConfiguration;
import io.github.adrian0511.prompt_link.exceptions.AiClientException;
import reactor.test.StepVerifier;

/**
 * Exercises the reactive service against a real HTTP server, SSE streaming included.
 *
 * <p>It pins down the three behaviours a mock-based test would never see: that the timeouts really
 * apply (and that they measure inactivity, not total time), that retries never duplicate text already
 * emitted, and that an error sent midway through the stream is not swallowed in silence.
 */
class ReactiveAiServiceTest {

    private static final String SUCCESSFUL_RESPONSE = """
            {"choices":[{"message":{"role":"assistant","content":"hello"}}]}""";

    private static final String ERROR_BODY = """
            {"error":{"message":"rate limit exceeded"}}""";

    /** Exactly as OpenRouter documents it: the error sits at the top level, and choices comes empty. */
    private static final String ERROR_EVENT = """
            data: {"id":"cmpl-abc","error":{"code":"server_error","message":"Provider disconnected unexpectedly"},"choices":[{"index":0,"delta":{"content":""},"finish_reason":"error"}]}

            """;

    private static final String RETRIES_ENABLED = "ai.retry.enabled=true";

    private HttpServer server;
    private final AtomicReference<Headers> receivedHeaders = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();

    private volatile int status = 200;
    private volatile String response = SUCCESSFUL_RESPONSE;

    /** Raw SSE blocks the server writes one by one, waiting {@link #pause} between them. */
    private volatile List<String> script;

    /** When set, the first attempt uses this script and later ones use the normal one. */
    private volatile List<String> firstAttemptScript;

    private volatile Duration pause = Duration.ZERO;

    /** When true the server stops writing and never closes: it simulates a server going silent. */
    private volatile boolean goesSilent;

    private volatile int initialFailures = 0;
    private volatile int failureStatus = 429;

    private ApplicationContextRunner runner;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // The handlers block (pauses, going silent): without a pool of their own they would block the
        // server itself.
        server.setExecutor(Executors.newCachedThreadPool());

        HttpHandler handler = exchange -> {
            receivedHeaders.set(exchange.getRequestHeaders());
            try (InputStream in = exchange.getRequestBody()) {
                receivedBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }

            int attempt = requests.incrementAndGet();
            if (attempt <= initialFailures) {
                respond(exchange, failureStatus, ERROR_BODY, "application/json");
                return;
            }

            List<String> toEmit = attempt == 1 && firstAttemptScript != null
                    ? firstAttemptScript
                    : script;
            if (toEmit != null) {
                emit(exchange, toEmit);
                return;
            }
            if (goesSilent) {
                // Headers yes, body never: the connection stays open and mute.
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                sleep(Duration.ofSeconds(30));
                return;
            }
            respond(exchange, status, response, "application/json");
        };

        server.createContext("/chat/completions", handler);
        server.createContext("/ping", handler);
        server.start();

        runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ReactiveAiAutoConfiguration.class))
                .withPropertyValues(
                        "ai.api-key=secret-key",
                        "ai.url=http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    // --- Streaming ---------------------------------------------------------------------------

    @Test
    void emitsTheStreamFragmentsInOrder() {
        script = List.of(
                event("{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}"),
                event("{\"choices\":[{\"delta\":{\"content\":\"Once \"}}]}"),
                event("{\"choices\":[{\"delta\":{\"content\":\"upon a time\"}}]}"),
                event("[DONE]"));

        runner.run(context -> StepVerifier
                // The first event only carries the role: it must not emit an empty fragment.
                .create(context.getBean(ReactiveAiService.class).stream("Tell me a story"))
                .expectNext("Once ")
                .expectNext("upon a time")
                .verifyComplete());
    }

    /** OpenRouter sends SSE keep-alive comments during long pauses. They are not tokens. */
    @Test
    void ignoresOpenRoutersKeepAlives() {
        script = List.of(
                ": OPENROUTER PROCESSING\n\n",
                event("{\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}"),
                ": OPENROUTER PROCESSING\n\n",
                event("[DONE]"));

        runner.run(context -> StepVerifier
                .create(context.getBean(ReactiveAiService.class).stream("hello"))
                .expectNext("hello")
                .verifyComplete());
    }

    @Test
    void asksTheApiForStreaming() {
        script = List.of(event("[DONE]"));

        runner.run(context -> {
            context.getBean(ReactiveAiService.class).stream("hello").blockLast();

            assertThat(receivedBody.get()).contains("\"stream\":true");
        });
    }

    @Test
    void doesNotAskForStreamingOnNormalCalls() {
        runner.run(context -> {
            context.getBean(ReactiveAiService.class).generate("hello").block();

            assertThat(receivedBody.get()).doesNotContain("stream");
        });
    }

    // --- The error that used to slip through mid-stream ---------------------------------------

    /**
     * OpenRouter can fail <em>after</em> having answered 200 and emitted tokens, sending the error
     * inside the stream. That event carries a choices entry with empty content, so it used to parse
     * fine, get filtered out as an empty fragment, and the stream would end normally: the user saw a
     * sentence cut in half and the application never knew.
     */
    @Test
    void propagatesAnErrorSentMidStream() {
        script = List.of(
                event("{\"choices\":[{\"delta\":{\"content\":\"Once \"}}]}"),
                ERROR_EVENT,
                event("[DONE]"));

        runner.run(context -> StepVerifier
                .create(context.getBean(ReactiveAiService.class).stream("Tell me a story"))
                .expectNext("Once ")
                .verifyErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(AiClientException.class);
                    AiClientException error = (AiClientException) e;
                    assertThat(error).hasMessageContaining("Provider disconnected unexpectedly");
                    // The code arrives as text ("server_error"), not as a number.
                    assertThat(error.getStatusCode()).isEqualTo(AiClientException.STREAM_ERROR);
                }));
    }

    // --- Timeouts ----------------------------------------------------------------------------

    /** Without a response timeout, a server that accepts and goes quiet leaves the Mono waiting forever. */
    @Test
    void abortsWhenTheResponseNeverArrives() {
        goesSilent = true;

        runner.withPropertyValues("ai.read-timeout=400ms").run(context -> StepVerifier
                .create(context.getBean(ReactiveAiService.class).generate("hello"))
                .verifyErrorSatisfies(e -> assertThat(((AiClientException) e).getStatusCode())
                        .isEqualTo(AiClientException.NETWORK_ERROR)));
    }

    @Test
    void abortsWhenTheStreamGoesSilentHalfway() {
        script = List.of(event("{\"choices\":[{\"delta\":{\"content\":\"Once \"}}]}"));
        goesSilent = true;

        runner.withPropertyValues("ai.read-timeout=400ms").run(context -> StepVerifier
                .create(context.getBean(ReactiveAiService.class).stream("hello"))
                .expectNext("Once ")
                .verifyErrorSatisfies(e -> assertThat(((AiClientException) e).getStatusCode())
                        .isEqualTo(AiClientException.NETWORK_ERROR)));
    }

    /**
     * The timeout has to measure inactivity, not total time: a long, legitimate answer that takes
     * longer than the read timeout to finish emitting cannot be cut off while text keeps arriving.
     * Here the stream lasts ~1.2s with a 500ms read timeout, and must complete.
     */
    @Test
    void doesNotCutOffASlowButLiveStream() {
        script = List.of(
                event("{\"choices\":[{\"delta\":{\"content\":\"one \"}}]}"),
                event("{\"choices\":[{\"delta\":{\"content\":\"two \"}}]}"),
                event("{\"choices\":[{\"delta\":{\"content\":\"three \"}}]}"),
                event("{\"choices\":[{\"delta\":{\"content\":\"four\"}}]}"),
                event("[DONE]"));
        pause = Duration.ofMillis(300);

        runner.withPropertyValues("ai.read-timeout=500ms").run(context -> StepVerifier
                .create(context.getBean(ReactiveAiService.class).stream("count"))
                .expectNext("one ", "two ", "three ", "four")
                .verifyComplete());
    }

    // --- Retries -----------------------------------------------------------------------------

    @Test
    void retriesNothingByDefault() {
        status = 429;
        response = ERROR_BODY;

        runner.run(context -> {
            StepVerifier.create(context.getBean(ReactiveAiService.class).generate("hello"))
                    .verifyErrorSatisfies(e -> assertThat(((AiClientException) e).getStatusCode())
                            .isEqualTo(429));

            assertThat(requests).hasValue(1);
        });
    }

    @Test
    void retriesGenerateWhenEnabled() {
        initialFailures = 1;
        failureStatus = 429;

        runner.withPropertyValues(RETRIES_ENABLED, "ai.retry.period=10ms").run(context -> {
            StepVerifier.create(context.getBean(ReactiveAiService.class).generate("hello"))
                    .assertNext(r -> assertThat(r.getContent()).isEqualTo("hello"))
                    .verifyComplete();

            assertThat(requests).hasValue(2);
        });
    }

    /** A failure before the first token can be retried: the user has not seen anything yet. */
    @Test
    void retriesAStreamThatFailsBeforeTheFirstToken() {
        initialFailures = 1;
        failureStatus = 429;
        script = List.of(
                event("{\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}"),
                event("[DONE]"));

        runner.withPropertyValues(RETRIES_ENABLED, "ai.retry.period=10ms").run(context -> {
            StepVerifier.create(context.getBean(ReactiveAiService.class).stream("hello"))
                    .expectNext("hello")
                    .verifyComplete();

            assertThat(requests).hasValue(2);
        });
    }

    /**
     * A provider failure midway through a stream is transient, and when it lands before the first
     * token nothing has been handed to the user: retrying it is both safe and correct.
     *
     * <p>It used not to be: carrying no numeric code, the error was classified as INVALID_RESPONSE and
     * isRetryable said no. The whole "only before the first token" machinery existed, and the most
     * common kind of error never got to use it.
     */
    @Test
    void retriesAStreamErrorThatArrivesBeforeTheFirstToken() {
        firstAttemptScript = List.of(ERROR_EVENT);
        script = List.of(
                event("{\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}"),
                event("[DONE]"));

        runner.withPropertyValues(RETRIES_ENABLED, "ai.retry.period=10ms").run(context -> {
            StepVerifier.create(context.getBean(ReactiveAiService.class).stream("hello"))
                    .expectNext("hello")
                    .verifyComplete();

            assertThat(requests).hasValue(2);
        });
    }

    /**
     * But when OpenRouter does give a numeric code, it keeps its usual meaning: a 402 (out of credits)
     * is not fixed by repeating the call, even if it arrives from inside the stream.
     */
    @Test
    void doesNotRetryAStreamErrorWithANonRetryableNumericCode() {
        firstAttemptScript = List.of("""
                data: {"error":{"code":402,"message":"insufficient credits"},"choices":[{"delta":{"content":""}}]}

                """);
        script = List.of(event("[DONE]"));

        runner.withPropertyValues(RETRIES_ENABLED, "ai.retry.period=10ms").run(context -> {
            StepVerifier.create(context.getBean(ReactiveAiService.class).stream("hello"))
                    .verifyErrorSatisfies(e -> assertThat(((AiClientException) e).getStatusCode())
                            .isEqualTo(402));

            assertThat(requests).hasValue(1);
        });
    }

    /**
     * Once tokens have been emitted, retrying would resend the answer from the beginning and the user
     * would see the text duplicated on screen. It fails, and it is not retried.
     */
    @Test
    void doesNotRetryAStreamThatAlreadyStartedEmitting() {
        script = List.of(
                event("{\"choices\":[{\"delta\":{\"content\":\"Once \"}}]}"),
                ERROR_EVENT);

        runner.withPropertyValues(RETRIES_ENABLED, "ai.retry.period=10ms").run(context -> {
            StepVerifier.create(context.getBean(ReactiveAiService.class).stream("hello"))
                    .expectNext("Once ")
                    .verifyError(AiClientException.class);

            assertThat(requests).hasValue(1);
        });
    }

    // --- Everything else ----------------------------------------------------------------------

    @Test
    void returnsTheWholeAnswerWithoutStreaming() {
        runner.run(context -> StepVerifier
                .create(context.getBean(ReactiveAiService.class).generate("how are you?"))
                .assertNext(answer -> assertThat(answer.getContent()).isEqualTo("hello"))
                .verifyComplete());
    }

    @Test
    void authenticatesTheCall() {
        runner.run(context -> {
            context.getBean(ReactiveAiService.class).generate("hello").block();

            assertThat(receivedHeaders.get().getFirst("Authorization"))
                    .isEqualTo("Bearer secret-key");
        });
    }

    /** The same error contract as the blocking service: status and body intact. */
    @Test
    void translatesApiErrorsPreservingStatusAndBody() {
        status = 429;
        response = "{\"error\":{\"message\":\"rate limit\"}}";

        runner.run(context -> StepVerifier
                .create(context.getBean(ReactiveAiService.class).generate("hello"))
                .verifyErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(AiClientException.class);
                    AiClientException error = (AiClientException) e;
                    assertThat(error.getStatusCode()).isEqualTo(429);
                    assertThat(error.getErrorBody()).contains("rate limit");
                }));
    }

    @Test
    void failsWithAClearMessageWhenTheApiKeyIsMissing() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ReactiveAiAutoConfiguration.class))
                .withPropertyValues("ai.url=http://127.0.0.1:" + server.getAddress().getPort())
                .run(context -> StepVerifier
                        .create(context.getBean(ReactiveAiService.class).generate("hello"))
                        .verifyErrorSatisfies(e -> assertThat(((AiClientException) e).getStatusCode())
                                .isEqualTo(AiClientException.CONFIGURATION_ERROR)));
    }

    /**
     * Regression of the API key leak, in its reactive flavour: if the Authorization header were added
     * to the application's shared WebClient.Builder instead of to a copy of our own, the key would
     * travel to every WebClient of whoever uses this library.
     */
    @Test
    void doesNotLeakTheApiKeyToTheApplicationsWebClient() {
        runner.withUserConfiguration(SharedBuilder.class).run(context -> {
            WebClient otherClient = context.getBean(WebClient.Builder.class)
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .build();

            otherClient.get().uri("/ping").retrieve().bodyToMono(String.class).block();

            Headers headers = receivedHeaders.get();
            assertThat(headers.getFirst("Authorization")).isNull();
            assertThat(headers.toString()).doesNotContain("secret-key");
        });
    }

    @Test
    void doesNotActivateWithoutWebFluxOnTheClasspath() {
        runner.withClassLoader(new FilteredClassLoader(WebClient.class))
                .run(context -> assertThat(context).doesNotHaveBean(ReactiveAiService.class));
    }

    // --- Server helpers -----------------------------------------------------------------------

    private static String event(String json) {
        return "data: " + json + "\n\n";
    }

    private void emit(HttpExchange exchange, List<String> blocks) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);

        OutputStream out = exchange.getResponseBody();
        for (String block : blocks) {
            sleep(pause);
            out.write(block.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        if (goesSilent) {
            sleep(Duration.ofSeconds(30));
        }
        out.close();
    }

    private static void respond(HttpExchange exchange, int status, String body, String contentType)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleep(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SharedBuilder {

        /** The builder an application would share across all its WebClients. */
        @Bean
        WebClient.Builder webClientBuilder() {
            return WebClient.builder();
        }
    }

}
