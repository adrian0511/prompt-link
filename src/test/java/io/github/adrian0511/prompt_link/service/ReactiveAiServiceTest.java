package io.github.adrian0511.prompt_link.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import io.github.adrian0511.prompt_link.config.ReactiveAiAutoConfiguration;
import io.github.adrian0511.prompt_link.exceptions.AiClientException;
import reactor.test.StepVerifier;

/**
 * Ejercita el servicio reactivo contra un servidor HTTP real, incluido el streaming SSE, que es la
 * razón de ser de este camino: comprobar que los fragmentos llegan de uno en uno y en orden.
 *
 * <p>Repite además el test de la fuga de la API key en la variante reactiva. El fallo original de
 * la librería fue exactamente este, en su versión Feign: si la cabecera Authorization se añadiera
 * al WebClient.Builder compartido de la aplicación en vez de a una copia propia, la clave viajaría
 * a todos los WebClient de quien use la librería.
 */
class ReactiveAiServiceTest {

    private static final String RESPUESTA_OK = """
            {"choices":[{"message":{"role":"assistant","content":"hola"}}]}""";

    private HttpServer server;
    private final AtomicReference<Headers> cabecerasRecibidas = new AtomicReference<>();
    private final AtomicReference<String> cuerpoRecibido = new AtomicReference<>();

    private volatile int status = 200;
    private volatile String respuesta = RESPUESTA_OK;
    private volatile boolean sse = false;

    private ApplicationContextRunner runner;

    @BeforeEach
    void arrancaElServidor() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpHandler handler = exchange -> {
            cabecerasRecibidas.set(exchange.getRequestHeaders());
            try (InputStream in = exchange.getRequestBody()) {
                cuerpoRecibido.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }

            byte[] cuerpo = respuesta.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add(
                    "Content-Type", sse ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(status, cuerpo.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(cuerpo);
                out.flush();
            }
        };
        server.createContext("/chat/completions", handler);
        server.createContext("/ping", handler);
        server.start();

        runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ReactiveAiAutoConfiguration.class))
                .withPropertyValues(
                        "ai.api-key=clave-secreta",
                        "ai.url=http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void paraElServidor() {
        server.stop(0);
    }

    @Test
    void emiteLosFragmentosDelStreamEnOrden() {
        sse = true;
        respuesta = """
                data: {"choices":[{"delta":{"role":"assistant"}}]}

                data: {"choices":[{"delta":{"content":"Había "}}]}

                data: {"choices":[{"delta":{"content":"una vez"}}]}

                data: [DONE]

                """;

        runner.run(context -> {
            ReactiveAiService service = context.getBean(ReactiveAiService.class);

            // El primer evento solo trae el rol, sin contenido: no debe emitir un fragmento vacío.
            StepVerifier.create(service.stream("Cuéntame un cuento"))
                    .expectNext("Había ")
                    .expectNext("una vez")
                    .verifyComplete();
        });
    }

    @Test
    void pideElStreamingALaApi() {
        sse = true;
        respuesta = """
                data: [DONE]

                """;

        runner.run(context -> {
            context.getBean(ReactiveAiService.class).stream("hola").blockLast();

            assertThat(cuerpoRecibido.get()).contains("\"stream\":true");
        });
    }

    @Test
    void noPideStreamingEnLasLlamadasNormales() {
        runner.run(context -> {
            context.getBean(ReactiveAiService.class).generate("hola").block();

            assertThat(cuerpoRecibido.get()).doesNotContain("stream");
        });
    }

    @Test
    void devuelveLaRespuestaCompletaSinStreaming() {
        runner.run(context -> StepVerifier
                .create(context.getBean(ReactiveAiService.class).generate("¿qué tal?"))
                .assertNext(respuesta -> assertThat(respuesta.getContent()).isEqualTo("hola"))
                .verifyComplete());
    }

    @Test
    void autenticaLaLlamada() {
        runner.run(context -> {
            context.getBean(ReactiveAiService.class).generate("hola").block();

            assertThat(cabecerasRecibidas.get().getFirst("Authorization"))
                    .isEqualTo("Bearer clave-secreta");
        });
    }

    /** El mismo contrato de errores que el servicio bloqueante: status y cuerpo intactos. */
    @Test
    void traduceLosErroresDeLaApiConservandoStatusYCuerpo() {
        status = 429;
        respuesta = "{\"error\":{\"message\":\"rate limit\"}}";

        runner.run(context -> StepVerifier
                .create(context.getBean(ReactiveAiService.class).generate("hola"))
                .verifyErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(AiClientException.class);
                    AiClientException error = (AiClientException) e;
                    assertThat(error.getStatusCode()).isEqualTo(429);
                    assertThat(error.getErrorBody()).contains("rate limit");
                }));
    }

    @Test
    void fallaConMensajeClaroSiFaltaLaApiKey() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ReactiveAiAutoConfiguration.class))
                .withPropertyValues("ai.url=http://127.0.0.1:" + server.getAddress().getPort())
                .run(context -> StepVerifier
                        .create(context.getBean(ReactiveAiService.class).generate("hola"))
                        .verifyErrorSatisfies(e -> assertThat(((AiClientException) e).getStatusCode())
                                .isEqualTo(AiClientException.CONFIGURATION_ERROR)));
    }

    /**
     * Regresión de la fuga de la API key, en su versión reactiva: la cabecera Authorization debe
     * estar en el WebClient de la librería y en ningún otro. Si se añadiera al WebClient.Builder
     * compartido de la aplicación, este test recibiría la clave en la llamada a otro servicio.
     */
    @Test
    void noFiltraLaApiKeyAlWebClientDeLaAplicacion() {
        runner.withUserConfiguration(BuilderCompartido.class).run(context -> {
            WebClient otroCliente = context.getBean(WebClient.Builder.class)
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .build();

            otroCliente.get().uri("/ping").retrieve().bodyToMono(String.class).block();

            Headers headers = cabecerasRecibidas.get();
            assertThat(headers.getFirst("Authorization")).isNull();
            assertThat(headers.toString()).doesNotContain("clave-secreta");
        });
    }

    /** Sin WebFlux en el classpath, la librería no debe registrar nada reactivo. */
    @Test
    void noSeActivaSinWebFluxEnElClasspath() {
        runner.withClassLoader(new FilteredClassLoader(WebClient.class))
                .run(context -> assertThat(context).doesNotHaveBean(ReactiveAiService.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class BuilderCompartido {

        /** El builder que compartiría la aplicación con todos sus WebClient. */
        @Bean
        WebClient.Builder webClientBuilder() {
            return WebClient.builder();
        }
    }

}
