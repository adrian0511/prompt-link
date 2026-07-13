package io.github.adrian0511.prompt_link.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;

import feign.Feign;
import io.github.adrian0511.prompt_link.client.AiClient;
import io.github.adrian0511.prompt_link.service.AiService;

/**
 * Registra el cliente de OpenRouter y {@link AiService} en la aplicación.
 *
 * <p>Los beans propios de Feign (interceptor, error decoder, timeouts) viven en
 * {@link AiFeignConfiguration}, que Spring Cloud carga solo en el contexto hijo del cliente. Nunca
 * deben declararse aquí: este contexto es el principal y Feign los heredaría en todos los clientes.
 */
@AutoConfiguration
@ConditionalOnClass(Feign.class)
@EnableConfigurationProperties(AiProperties.class)
@EnableFeignClients(clients = AiClient.class)
public class AiClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    AiService aiService(AiClient aiClient, AiProperties properties) {
        return new AiService(aiClient, properties);
    }

}
