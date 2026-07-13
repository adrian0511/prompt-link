package io.github.adrian0511.prompt_link.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.adrian0511.prompt_link.service.ReactiveAiService;
import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;

/**
 * Registers {@link ReactiveAiService} in applications that have WebFlux on the classpath.
 *
 * <p>WebFlux is an optional dependency of this library: an application on MVC does not drag in
 * reactor-netty and this auto-configuration simply never activates. To enable streaming, the
 * consumer only has to add {@code spring-boot-starter-webflux}.
 *
 * <p>The {@link WebClient} is built <strong>for this library alone</strong> and is not exposed as a
 * bean. That is deliberate, and for the same reason the Feign configuration lives apart: if the
 * Authorization header were added to the application's own {@code WebClient.Builder}, or if a global
 * builder were published here, the OpenRouter API key would travel to every other service the
 * application called with WebClient.
 */
@AutoConfiguration
@ConditionalOnClass(WebClient.class)
@EnableConfigurationProperties(AiProperties.class)
public class ReactiveAiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ReactiveAiService reactiveAiService(
            AiProperties properties,
            ObjectProvider<WebClient.Builder> builders,
            ObjectProvider<ObjectMapper> objectMappers) {

        // Start from the application's builder when there is one, to inherit its codecs and
        // connection settings, but clone it: the bean is a prototype, so the Authorization header
        // added below stays in OUR copy and does not touch the application's other WebClients.
        WebClient.Builder builder = builders.getIfAvailable(WebClient::builder).clone();

        WebClient webClient = builder
                .baseUrl(properties.getUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient(properties)))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .defaultHeader("HTTP-Referer", properties.getHost())
                .defaultHeader("X-Title", properties.getTitle())
                .build();

        return new ReactiveAiService(
                webClient, properties, objectMappers.getIfAvailable(ObjectMapper::new));
    }

    /**
     * Applies {@code ai.connect-timeout} and {@code ai.read-timeout} to the reactive client.
     *
     * <p>Reactor Netty ships with no response timeout at all: without this, a server that accepts
     * the connection and then goes silent would leave the {@code Mono} waiting forever. That was the
     * worst flavour of the bug, because both properties exist and are documented, so anyone
     * configuring them believed they did something.
     *
     * <p>{@code responseTimeout} measures the time <strong>between network reads</strong>, not the
     * total: a long, legitimate answer taking minutes to be emitted is not cut off as long as
     * something keeps arriving. That is exactly the semantics streaming needs, and it also counts the
     * keep-alives ({@code : OPENROUTER PROCESSING}) OpenRouter sends during long pauses, which are
     * visible at this level even though the SSE decoder discards them later.
     */
    private static HttpClient httpClient(AiProperties properties) {
        return HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        (int) properties.getConnectTimeout().toMillis())
                .responseTimeout(properties.getReadTimeout());
    }

}
