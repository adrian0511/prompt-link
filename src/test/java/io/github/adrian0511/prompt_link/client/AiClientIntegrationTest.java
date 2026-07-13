package io.github.adrian0511.prompt_link.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import io.github.adrian0511.prompt_link.config.AiClientAutoConfiguration;
import io.github.adrian0511.prompt_link.dto.AiResponse;
import io.github.adrian0511.prompt_link.exceptions.AiClientException;
import io.github.adrian0511.prompt_link.service.AiService;

/**
 * Checks, against a real HTTP server, what the context tests cannot see: that the interceptor and the
 * error decoder really do apply to the OpenRouter client.
 *
 * <p>This is the other half of the security test. Taking them out of the main context closes the API
 * key leak, but that is only worth anything if they still reach the client's own context: otherwise
 * the library would stop authenticating and every unit test would stay green.
 */
class AiClientIntegrationTest {

    private static final String SUCCESSFUL_RESPONSE = """
            {"choices":[{"message":{"role":"assistant","content":"hello"}}]}""";

    private static final String ERROR_BODY = """
            {"error":{"message":"rate limit exceeded"}}""";

    /** Fast retries: what these tests check is how many times we call, not how long we wait. */
    private static final String[] FAST_RETRIES = {
        "ai.retry.enabled=true", "ai.retry.period=10ms", "ai.retry.max-period=50ms"
    };

    private HttpServer server;
    private final AtomicReference<Headers> receivedHeaders = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();

    private volatile int status = 200;
    private volatile String response = SUCCESSFUL_RESPONSE;

    /** The first N attempts fail with {@link #failureStatus}; the rest answer normally. */
    private volatile int initialFailures = 0;
    private volatile int failureStatus = 429;

    private final AtomicInteger requests = new AtomicInteger();

    private ApplicationContextRunner runner;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpHandler recordAndRespond = exchange -> {
            receivedHeaders.set(exchange.getRequestHeaders());
            try (InputStream in = exchange.getRequestBody()) {
                receivedBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }

            boolean fails = requests.incrementAndGet() <= initialFailures;
            byte[] body = (fails ? ERROR_BODY : response).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(fails ? failureStatus : status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        };
        server.createContext("/chat/completions", recordAndRespond);
        server.createContext("/ping", recordAndRespond);
        server.start();

        runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        HttpMessageConvertersAutoConfiguration.class,
                        FeignAutoConfiguration.class,
                        AiClientAutoConfiguration.class))
                .withPropertyValues(
                        "ai.api-key=secret-key",
                        "ai.title=My App",
                        "ai.host=https://my-app.example",
                        "ai.url=http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void authenticatesTheCallWithTheApiKey() {
        runner.run(context -> {
            context.getBean(AiService.class).generate("how are you?");

            Headers headers = receivedHeaders.get();
            assertThat(headers.getFirst("Authorization")).isEqualTo("Bearer secret-key");
            assertThat(headers.getFirst("Http-referer")).isEqualTo("https://my-app.example");
            assertThat(headers.getFirst("X-title")).isEqualTo("My App");
        });
    }

    @Test
    void serializesTheRequestTheWayOpenRouterExpects() {
        runner.withPropertyValues("ai.model=openai/gpt-4o-mini", "ai.max-tokens=256")
                .run(context -> {
                    context.getBean(AiService.class).generate("how are you?");

                    assertThat(receivedBody.get())
                            .contains("\"model\":\"openai/gpt-4o-mini\"")
                            .contains("\"max_tokens\":256")
                            .contains("\"role\":\"user\"")
                            .contains("\"content\":\"how are you?\"")
                            // temperature was not configured: it must not travel, so that the model
                            // applies its own default.
                            .doesNotContain("temperature");
                });
    }

    @Test
    void returnsTheContentOfASuccessfulResponse() {
        runner.run(context -> assertThat(context.getBean(AiService.class)
                .generate("how are you?")
                .getContent()).isEqualTo("hello"));
    }

    /**
     * The original security bug, reproduced as it happened: an application that, on top of this
     * library, has its own Feign client pointing at some other service.
     *
     * <p>When the interceptor lived in the main context, Feign applied it to that other client too and
     * the OpenRouter API key ended up travelling to a third party. This checks that the call to the
     * other service carries no trace of the key.
     */
    @Test
    void doesNotLeakTheApiKeyToOtherFeignClientsOfTheApplication() {
        runner.withUserConfiguration(OtherServiceConfig.class).run(context -> {
            context.getBean(OtherService.class).ping();

            Headers headers = receivedHeaders.get();
            assertThat(headers.getFirst("Authorization")).isNull();
            assertThat(headers.getFirst("X-title")).isNull();
            assertThat(headers.toString()).doesNotContain("secret-key");
        });
    }

    @Test
    void translatesApiErrorsPreservingStatusAndBody() {
        status = 429;
        response = "{\"error\":{\"message\":\"rate limit\"}}";

        runner.run(context -> {
            AiService service = context.getBean(AiService.class);

            assertThatExceptionOfType(AiClientException.class)
                    .isThrownBy(() -> service.generate("how are you?"))
                    .satisfies(e -> {
                        assertThat(e.getStatusCode()).isEqualTo(429);
                        assertThat(e.getErrorBody()).contains("rate limit");
                        assertThat(e.isHttpError()).isTrue();
                    });
        });
    }

    @Test
    void retriesNothingByDefault() {
        status = 429;
        response = ERROR_BODY;

        runner.run(context -> {
            AiService service = context.getBean(AiService.class);

            assertThatExceptionOfType(AiClientException.class)
                    .isThrownBy(() -> service.generate("how are you?"))
                    .satisfies(e -> assertThat(e.getStatusCode()).isEqualTo(429));

            assertThat(requests).hasValue(1);
        });
    }

    @Test
    void retriesRateLimitsWhenEnabled() {
        initialFailures = 1;
        failureStatus = 429;

        runner.withPropertyValues(FAST_RETRIES).run(context -> {
            AiResponse response = context.getBean(AiService.class).generate("how are you?");

            assertThat(response.getContent()).isEqualTo("hello");
            assertThat(requests).hasValue(2);
        });
    }

    @Test
    void retriesServerErrors() {
        initialFailures = 2;
        failureStatus = 503;

        runner.withPropertyValues(FAST_RETRIES)
                .withPropertyValues("ai.retry.max-attempts=3")
                .run(context -> {
                    context.getBean(AiService.class).generate("how are you?");

                    assertThat(requests).hasValue(3);
                });
    }

    /**
     * Once the attempts run out, the caller must still see an AiClientException with the real status
     * and body, not the RetryableException Feign wraps retryable failures in.
     */
    @Test
    void keepsTheOriginalErrorAfterExhaustingTheAttempts() {
        status = 429;
        response = ERROR_BODY;

        runner.withPropertyValues(FAST_RETRIES)
                .withPropertyValues("ai.retry.max-attempts=3")
                .run(context -> {
                    AiService service = context.getBean(AiService.class);

                    assertThatExceptionOfType(AiClientException.class)
                            .isThrownBy(() -> service.generate("how are you?"))
                            .satisfies(e -> {
                                assertThat(e.getStatusCode()).isEqualTo(429);
                                assertThat(e.getErrorBody()).contains("rate limit exceeded");
                            });

                    assertThat(requests).hasValue(3);
                });
    }

    /** Repeating a call with a bad key or no credits gives the same error: it is not retried. */
    @Test
    void doesNotRetryCredentialErrors() {
        status = 401;
        response = "{\"error\":{\"message\":\"invalid api key\"}}";

        runner.withPropertyValues(FAST_RETRIES).run(context -> {
            AiService service = context.getBean(AiService.class);

            assertThatExceptionOfType(AiClientException.class)
                    .isThrownBy(() -> service.generate("how are you?"))
                    .satisfies(e -> assertThat(e.getStatusCode()).isEqualTo(401));

            assertThat(requests).hasValue(1);
        });
    }

    /** Any old Feign client of the application, unrelated to this library. */
    @FeignClient(name = "other-service", url = "${ai.url}")
    interface OtherService {

        @GetMapping("/ping")
        String ping();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableFeignClients(clients = OtherService.class)
    static class OtherServiceConfig {
    }

}
