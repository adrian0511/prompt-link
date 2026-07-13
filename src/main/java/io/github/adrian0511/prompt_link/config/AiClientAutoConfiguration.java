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
 * Registers the OpenRouter client and {@link AiService} in the application.
 *
 * <p>Feign's own beans (interceptor, error decoder, timeouts, retries) live in
 * {@link AiFeignConfiguration}, which Spring Cloud loads only in the client's child context. They
 * must never be declared here: this is the main context, and Feign would inherit them into every
 * client of the application.
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
