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
 * Ejercita el servicio reactivo contra un servidor HTTP real, incluido el streaming SSE.
 *
 * <p>Aquí se fijan los tres comportamientos que un test con mocks no vería: que los timeouts se
 * apliquen de verdad (y que sean por inactividad, no totales), que los reintentos no dupliquen texto
 * ya emitido, y que un error mandado a mitad del stream no se trague en silencio.
 */
class ReactiveAiServiceTest {

    private static final String RESPUESTA_OK = """
            {"choices":[{"message":{"role":"assistant","content":"hola"}}]}""";

    private static final String CUERPO_FALLO = """
            {"error":{"message":"rate limit exceeded"}}""";

    /** Tal cual lo documenta OpenRouter: el error va arriba, y el choices viene vacío. */
    private static final String EVENTO_DE_ERROR = """
            data: {"id":"cmpl-abc","error":{"code":"server_error","message":"Provider disconnected unexpectedly"},"choices":[{"index":0,"delta":{"content":""},"finish_reason":"error"}]}

            """;

    private static final String REINTENTOS_RAPIDOS = "ai.retry.enabled=true";

    private HttpServer server;
    private final AtomicReference<Headers> cabecerasRecibidas = new AtomicReference<>();
    private final AtomicReference<String> cuerpoRecibido = new AtomicReference<>();
    private final AtomicInteger peticiones = new AtomicInteger();

    private volatile int status = 200;
    private volatile String respuesta = RESPUESTA_OK;
    private volatile boolean sse = false;

    /** Bloques SSE crudos que el servidor irá escribiendo, uno a uno, con {@link #pausa} entre ellos. */
    private volatile List<String> guion;

    /** Si está puesto, el primer intento usa este guion y los siguientes el normal. */
    private volatile List<String> guionDelPrimerIntento;

    private volatile Duration pausa = Duration.ZERO;

    /** Si es true, el servidor deja de escribir y no cierra: simula un servidor que se queda mudo. */
    private volatile boolean seQuedaMudo;

    private volatile int fallosIniciales = 0;
    private volatile int statusFallo = 429;

    private ApplicationContextRunner runner;

    @BeforeEach
    void arrancaElServidor() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Los handlers bloquean (pausas, quedarse mudo): sin un pool propio bloquearían el servidor.
        server.setExecutor(Executors.newCachedThreadPool());

        HttpHandler handler = exchange -> {
            cabecerasRecibidas.set(exchange.getRequestHeaders());
            try (InputStream in = exchange.getRequestBody()) {
                cuerpoRecibido.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }

            int intento = peticiones.incrementAndGet();
            if (intento <= fallosIniciales) {
                responder(exchange, statusFallo, CUERPO_FALLO, "application/json");
                return;
            }

            List<String> aEmitir = intento == 1 && guionDelPrimerIntento != null
                    ? guionDelPrimerIntento
                    : guion;
            if (aEmitir != null) {
                emitirGuion(exchange, aEmitir);
                return;
            }
            if (seQuedaMudo) {
                // Cabeceras sí, cuerpo nunca: la conexión queda abierta y muda.
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                dormir(Duration.ofSeconds(30));
                return;
            }
            responder(exchange, status, respuesta,
                    sse ? "text/event-stream" : "application/json");
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

    // --- Streaming ---------------------------------------------------------------------------

    @Test
    void emiteLosFragmentosDelStreamEnOrden() {
        guion = List.of(
                evento("{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}"),
                evento("{\"choices\":[{\"delta\":{\"content\":\"Había \"}}]}"),
                evento("{\"choices\":[{\"delta\":{\"content\":\"una vez\"}}]}"),
                evento("[DONE]"));

        runner.run(context -> StepVerifier
                // El primer evento solo trae el rol: no debe emitir un fragmento vacío.
                .create(context.getBean(ReactiveAiService.class).stream("Cuéntame un cuento"))
                .expectNext("Había ")
                .expectNext("una vez")
                .verifyComplete());
    }

    /** OpenRouter manda comentarios SSE de keep-alive durante las pausas largas. No son tokens. */
    @Test
    void ignoraLosKeepAliveDeOpenRouter() {
        guion = List.of(
                ": OPENROUTER PROCESSING\n\n",
                evento("{\"choices\":[{\"delta\":{\"content\":\"hola\"}}]}"),
                ": OPENROUTER PROCESSING\n\n",
                evento("[DONE]"));

        runner.run(context -> StepVerifier
                .create(context.getBean(ReactiveAiService.class).stream("hola"))
                .expectNext("hola")
                .verifyComplete());
    }

    @Test
    void pideElStreamingALaApi() {
        guion = List.of(evento("[DONE]"));

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

    // --- El error que se colaba a mitad de stream --------------------------------------------

    /**
     * OpenRouter puede fallar <em>después</em> de haber respondido 200 y de haber emitido tokens,
     * mandando el error dentro del stream. Ese evento trae un choices con contenido vacío, así que
     * antes se parseaba, se filtraba como fragmento vacío y el stream terminaba con normalidad: el
     * usuario veía la frase cortada y la aplicación no se enteraba de nada.
     */
    @Test
    void propagaUnErrorMandadoAMitadDelStream() {
        guion = List.of(
                evento("{\"choices\":[{\"delta\":{\"content\":\"Había \"}}]}"),
                EVENTO_DE_ERROR,
                evento("[DONE]"));

        runner.run(context -> StepVerifier
                .create(context.getBean(ReactiveAiService.class).stream("Cuéntame un cuento"))
                .expectNext("Había ")
                .verifyErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(AiClientException.class);
                    AiClientException error = (AiClientException) e;
                    assertThat(error).hasMessageContaining("Provider disconnected unexpectedly");
                    // El code viene como texto ("server_error"), no como número.
                    assertThat(error.getStatusCode()).isEqualTo(AiClientException.STREAM_ERROR);
                }));
    }

    /**
     * Un fallo del proveedor a mitad de stream es transitorio, y si llega antes del primer token no
     * se ha entregado nada al usuario: reintentarlo es seguro y correcto.
     *
     * <p>No lo era: al no traer código numérico, el error se clasificaba como INVALID_RESPONSE y
     * esRreintentable decía que no. La maquinaria del "solo antes del primer token" existía y la
     * clase de error más común no llegaba nunca a usarla.
     */
    @Test
    void reintentaUnErrorDeStreamQueLlegaAntesDelPrimerToken() {
        guionDelPrimerIntento = List.of(EVENTO_DE_ERROR);
        guion = List.of(
                evento("{\"choices\":[{\"delta\":{\"content\":\"hola\"}}]}"),
                evento("[DONE]"));

        runner.withPropertyValues(REINTENTOS_RAPIDOS, "ai.retry.period=10ms").run(context -> {
            StepVerifier.create(context.getBean(ReactiveAiService.class).stream("hola"))
                    .expectNext("hola")
                    .verifyComplete();

            assertThat(peticiones).hasValue(2);
        });
    }

    /**
     * Pero si OpenRouter sí da un código numérico, manda su significado de siempre: un 402 (sin
     * créditos) no se arregla repitiendo la llamada, aunque llegue por dentro del stream.
     */
    @Test
    void noReintentaUnErrorDeStreamConCodigoNumericoNoReintentable() {
        guionDelPrimerIntento = List.of("""
                data: {"error":{"code":402,"message":"insufficient credits"},"choices":[{"delta":{"content":""}}]}

                """);
        guion = List.of(evento("[DONE]"));

        runner.withPropertyValues(REINTENTOS_RAPIDOS, "ai.retry.period=10ms").run(context -> {
            StepVerifier.create(context.getBean(ReactiveAiService.class).stream("hola"))
                    .verifyErrorSatisfies(e -> assertThat(((AiClientException) e).getStatusCode())
                            .isEqualTo(402));

            assertThat(peticiones).hasValue(1);
        });
    }

    // --- Timeouts ----------------------------------------------------------------------------

    /** Sin timeout de respuesta, un servidor que acepta y se calla dejaría el Mono esperando siempre. */
    @Test
    void abortaSiLaRespuestaNoLlegaNunca() {
        seQuedaMudo = true;

        runner.withPropertyValues("ai.read-timeout=400ms").run(context -> StepVerifier
                .create(context.getBean(ReactiveAiService.class).generate("hola"))
                .verifyErrorSatisfies(e -> assertThat(((AiClientException) e).getStatusCode())
                        .isEqualTo(AiClientException.NETWORK_ERROR)));
    }

    @Test
    void abortaSiElStreamSeQuedaMudoAMedias() {
        guion = List.of(evento("{\"choices\":[{\"delta\":{\"content\":\"Había \"}}]}"));
        seQuedaMudo = true;

        runner.withPropertyValues("ai.read-timeout=400ms").run(context -> StepVerifier
                .create(context.getBean(ReactiveAiService.class).stream("hola"))
                .expectNext("Había ")
                .verifyErrorSatisfies(e -> assertThat(((AiClientException) e).getStatusCode())
                        .isEqualTo(AiClientException.NETWORK_ERROR)));
    }

    /**
     * El timeout tiene que ser por inactividad, no total: una respuesta larga y legítima que tarde
     * más que el read-timeout en terminar de emitirse no se puede cortar en seco mientras siga
     * llegando texto. Aquí el stream dura ~1,2s con un read-timeout de 500ms, y debe completarse.
     */
    @Test
    void noCortaUnStreamLentoQueSigueVivo() {
        guion = List.of(
                evento("{\"choices\":[{\"delta\":{\"content\":\"uno \"}}]}"),
                evento("{\"choices\":[{\"delta\":{\"content\":\"dos \"}}]}"),
                evento("{\"choices\":[{\"delta\":{\"content\":\"tres \"}}]}"),
                evento("{\"choices\":[{\"delta\":{\"content\":\"cuatro\"}}]}"),
                evento("[DONE]"));
        pausa = Duration.ofMillis(300);

        runner.withPropertyValues("ai.read-timeout=500ms").run(context -> StepVerifier
                .create(context.getBean(ReactiveAiService.class).stream("cuenta"))
                .expectNext("uno ", "dos ", "tres ", "cuatro")
                .verifyComplete());
    }

    // --- Reintentos --------------------------------------------------------------------------

    @Test
    void porDefectoNoReintenta() {
        status = 429;
        respuesta = CUERPO_FALLO;

        runner.run(context -> {
            StepVerifier.create(context.getBean(ReactiveAiService.class).generate("hola"))
                    .verifyErrorSatisfies(e -> assertThat(((AiClientException) e).getStatusCode())
                            .isEqualTo(429));

            assertThat(peticiones).hasValue(1);
        });
    }

    @Test
    void reintentaGenerateCuandoSeActiva() {
        fallosIniciales = 1;
        statusFallo = 429;

        runner.withPropertyValues(REINTENTOS_RAPIDOS, "ai.retry.period=10ms").run(context -> {
            StepVerifier.create(context.getBean(ReactiveAiService.class).generate("hola"))
                    .assertNext(r -> assertThat(r.getContent()).isEqualTo("hola"))
                    .verifyComplete();

            assertThat(peticiones).hasValue(2);
        });
    }

    /** Un fallo antes del primer token sí se puede reintentar: el usuario no ha visto nada aún. */
    @Test
    void reintentaUnStreamQueFallaAntesDelPrimerToken() {
        fallosIniciales = 1;
        statusFallo = 429;
        guion = List.of(
                evento("{\"choices\":[{\"delta\":{\"content\":\"hola\"}}]}"),
                evento("[DONE]"));

        runner.withPropertyValues(REINTENTOS_RAPIDOS, "ai.retry.period=10ms").run(context -> {
            StepVerifier.create(context.getBean(ReactiveAiService.class).stream("hola"))
                    .expectNext("hola")
                    .verifyComplete();

            assertThat(peticiones).hasValue(2);
        });
    }

    /**
     * Pero si ya se emitieron tokens, reintentar reenviaría la respuesta desde el principio y el
     * usuario vería el texto duplicado en pantalla. Se falla, y no se reintenta.
     */
    @Test
    void noReintentaUnStreamQueYaEmpezoAEmitir() {
        guion = List.of(
                evento("{\"choices\":[{\"delta\":{\"content\":\"Había \"}}]}"),
                EVENTO_DE_ERROR);

        runner.withPropertyValues(REINTENTOS_RAPIDOS, "ai.retry.period=10ms").run(context -> {
            StepVerifier.create(context.getBean(ReactiveAiService.class).stream("hola"))
                    .expectNext("Había ")
                    .verifyError(AiClientException.class);

            assertThat(peticiones).hasValue(1);
        });
    }

    // --- Lo demás ----------------------------------------------------------------------------

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
     * Regresión de la fuga de la API key, en su versión reactiva: si la cabecera Authorization se
     * añadiera al WebClient.Builder compartido de la aplicación en vez de a una copia propia, la
     * clave viajaría a todos los WebClient de quien use la librería.
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

    @Test
    void noSeActivaSinWebFluxEnElClasspath() {
        runner.withClassLoader(new FilteredClassLoader(WebClient.class))
                .run(context -> assertThat(context).doesNotHaveBean(ReactiveAiService.class));
    }

    // --- Utilidades del servidor -------------------------------------------------------------

    private static String evento(String json) {
        return "data: " + json + "\n\n";
    }

    private void emitirGuion(HttpExchange exchange, List<String> bloques) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);

        OutputStream out = exchange.getResponseBody();
        for (String bloque : bloques) {
            dormir(pausa);
            out.write(bloque.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        if (seQuedaMudo) {
            dormir(Duration.ofSeconds(30));
        }
        out.close();
    }

    private static void responder(HttpExchange exchange, int status, String cuerpo, String tipo)
            throws IOException {
        byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", tipo);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void dormir(Duration duracion) {
        if (duracion.isZero() || duracion.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duracion.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
