package io.github.adrian0511.prompt_link.config;

import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import feign.Request;
import feign.RequestInterceptor;
import feign.codec.ErrorDecoder;
import io.github.adrian0511.prompt_link.exceptions.AiErrorDecoder;

/**
 * Configuración del cliente Feign de OpenRouter.
 *
 * <p>Esta clase <strong>no</strong> lleva {@code @Configuration} a propósito: Spring Cloud la
 * registra únicamente en el contexto hijo del cliente {@code openrouter}. Si sus beans vivieran en
 * el contexto principal, Feign los heredaría en <em>todos</em> los clientes de la aplicación
 * (resuelve interceptores y error decoders mirando también a los contextos ancestros), y la
 * cabecera Authorization con la API key de OpenRouter viajaría a cualquier otro servicio que la
 * aplicación llamase por Feign.
 */
public class AiFeignConfiguration {

    @Bean
    @ConditionalOnMissingBean
    RequestInterceptor aiRequestInterceptor(AiProperties properties) {
        return template -> {
            template.header("Authorization", "Bearer " + properties.getApiKey());
            template.header("HTTP-Referer", properties.getHost());
            template.header("X-Title", properties.getTitle());
        };
    }

    @Bean
    @ConditionalOnMissingBean
    ErrorDecoder aiErrorDecoder() {
        return new AiErrorDecoder();
    }

    @Bean
    @ConditionalOnMissingBean
    Request.Options aiRequestOptions(AiProperties properties) {
        return new Request.Options(
                properties.getConnectTimeout().toMillis(), TimeUnit.MILLISECONDS,
                properties.getReadTimeout().toMillis(), TimeUnit.MILLISECONDS,
                true);
    }

}
