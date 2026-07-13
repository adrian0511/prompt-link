package io.github.adrian0511.prompt_link.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

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
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;

import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import io.github.adrian0511.prompt_link.config.AiClientAutoConfiguration;
import io.github.adrian0511.prompt_link.exceptions.AiClientException;
import io.github.adrian0511.prompt_link.service.AiService;

/**
 * Comprueba, contra un servidor HTTP real, lo que los tests de contexto no pueden ver: que el
 * interceptor y el error decoder sí se aplican al cliente de OpenRouter.
 *
 * <p>Es la otra mitad del test de seguridad. Sacarlos del contexto principal cierra la fuga de la
 * API key, pero solo sirve si siguen llegando al contexto hijo del cliente: si no, la librería
 * dejaría de autenticarse y todos los tests de unidad seguirían en verde.
 */
class AiClientIntegrationTest {

    private static final String RESPUESTA_OK = """
            {"choices":[{"message":{"role":"assistant","content":"hola"}}]}""";

    private HttpServer server;
    private final AtomicReference<Headers> cabecerasRecibidas = new AtomicReference<>();
    private final AtomicReference<String> cuerpoRecibido = new AtomicReference<>();

    private volatile int status = 200;
    private volatile String respuesta = RESPUESTA_OK;

    private ApplicationContextRunner runner;

    @BeforeEach
    void arrancaElServidor() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpHandler grabaYResponde = exchange -> {
            cabecerasRecibidas.set(exchange.getRequestHeaders());
            try (InputStream in = exchange.getRequestBody()) {
                cuerpoRecibido.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }

            byte[] cuerpo = respuesta.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, cuerpo.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(cuerpo);
            }
        };
        server.createContext("/chat/completions", grabaYResponde);
        server.createContext("/ping", grabaYResponde);
        server.start();

        runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        HttpMessageConvertersAutoConfiguration.class,
                        FeignAutoConfiguration.class,
                        AiClientAutoConfiguration.class))
                .withPropertyValues(
                        "ai.api-key=clave-secreta",
                        "ai.title=Mi App",
                        "ai.host=https://mi-app.example",
                        "ai.url=http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void paraElServidor() {
        server.stop(0);
    }

    @Test
    void autenticaLaLlamadaConLaApiKey() {
        runner.run(context -> {
            context.getBean(AiService.class).generate("¿qué tal?");

            Headers headers = cabecerasRecibidas.get();
            assertThat(headers.getFirst("Authorization")).isEqualTo("Bearer clave-secreta");
            assertThat(headers.getFirst("Http-referer")).isEqualTo("https://mi-app.example");
            assertThat(headers.getFirst("X-title")).isEqualTo("Mi App");
        });
    }

    @Test
    void serializaLaPeticionComoEsperaOpenRouter() {
        runner.withPropertyValues("ai.model=openai/gpt-4o-mini", "ai.max-tokens=256")
                .run(context -> {
                    context.getBean(AiService.class).generate("¿qué tal?");

                    assertThat(cuerpoRecibido.get())
                            .contains("\"model\":\"openai/gpt-4o-mini\"")
                            .contains("\"max_tokens\":256")
                            .contains("\"role\":\"user\"")
                            .contains("\"content\":\"¿qué tal?\"")
                            // temperature no se configuró: no debe viajar, para que el modelo
                            // aplique su propio valor por defecto.
                            .doesNotContain("temperature");
                });
    }

    @Test
    void devuelveElContenidoDeUnaRespuestaCorrecta() {
        runner.run(context -> assertThat(context.getBean(AiService.class)
                .generate("¿qué tal?")
                .getContent()).isEqualTo("hola"));
    }

    /**
     * El fallo de seguridad original, reproducido tal cual: una aplicación que, además de esta
     * librería, tiene su propio cliente Feign hacia otro servicio.
     *
     * <p>Cuando el interceptor vivía en el contexto principal, Feign se lo aplicaba también a ese
     * otro cliente y la API key de OpenRouter acababa viajando a un tercero. Aquí se comprueba que
     * la llamada al otro servicio sale sin rastro de la clave.
     */
    @Test
    void noFiltraLaApiKeyAOtrosClientesFeignDeLaAplicacion() {
        runner.withUserConfiguration(OtroServicioConfig.class).run(context -> {
            context.getBean(OtroServicio.class).ping();

            Headers headers = cabecerasRecibidas.get();
            assertThat(headers.getFirst("Authorization")).isNull();
            assertThat(headers.getFirst("X-title")).isNull();
            assertThat(headers.toString()).doesNotContain("clave-secreta");
        });
    }

    @Test
    void traduceLosErroresDeLaApiConservandoStatusYCuerpo() {
        status = 429;
        respuesta = "{\"error\":{\"message\":\"rate limit\"}}";

        runner.run(context -> {
            AiService service = context.getBean(AiService.class);

            assertThatExceptionOfType(AiClientException.class)
                    .isThrownBy(() -> service.generate("¿qué tal?"))
                    .satisfies(e -> {
                        assertThat(e.getStatusCode()).isEqualTo(429);
                        assertThat(e.getErrorBody()).contains("rate limit");
                        assertThat(e.isHttpError()).isTrue();
                    });
        });
    }

    /** Un cliente Feign cualquiera de la aplicación, ajeno a esta librería. */
    @FeignClient(name = "otro-servicio", url = "${ai.url}")
    interface OtroServicio {

        @GetMapping("/ping")
        String ping();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableFeignClients(clients = OtroServicio.class)
    static class OtroServicioConfig {
    }

}
