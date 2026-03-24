package io.github.adrian0511.prompt_link.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import feign.RequestInterceptor;
import feign.codec.ErrorDecoder;
import io.github.adrian0511.prompt_link.client.AiClient;
import io.github.adrian0511.prompt_link.exceptions.AiErrorDecoder;
import io.github.adrian0511.prompt_link.service.AiService;

@Configuration
@EnableConfigurationProperties(AiProperties.class)
@EnableFeignClients(basePackageClasses = AiClient.class)
public class AiClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    RequestInterceptor aiRequestInterceptor(AiProperties properties) {
        return template -> {
            template.header("Authorization", "Bearer " + properties.getApiKey());
            template.header("HTTP-Referer", properties.getHost());
            template.header("X-Title", "Spring AI Client");
        };
    }

    @Bean
    @ConditionalOnMissingBean
    ErrorDecoder errorDecoder() {
        return new AiErrorDecoder();
    }

    @Bean
    @ConditionalOnMissingBean
    AiService aiService(AiClient aiClient, AiProperties properties) {
        return new AiService(aiClient, properties);
    }

}
