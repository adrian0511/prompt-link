package io.github.adrian0511.prompt_link.config;

import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import feign.Request;
import feign.RequestInterceptor;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import io.github.adrian0511.prompt_link.exceptions.AiErrorDecoder;

/**
 * Configuration of the OpenRouter Feign client.
 *
 * <p>This class deliberately carries <strong>no</strong> {@code @Configuration}: Spring Cloud
 * registers it only in the child context of the {@code openrouter} client. If its beans lived in the
 * main context, Feign would inherit them in <em>every</em> client of the application — it resolves
 * interceptors and error decoders by also looking at ancestor contexts — and the Authorization
 * header carrying the OpenRouter API key would travel to any other service the application called
 * over Feign.
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

    /**
     * Feign retries nothing by default, and it stays that way unless you enable it with
     * {@code ai.retry.enabled}. Once enabled, it retries whatever {@code AiErrorDecoder} marks as
     * retryable (429 and 5xx) plus network failures, with exponential backoff.
     */
    @Bean
    @ConditionalOnMissingBean
    Retryer aiRetryer(AiProperties properties) {
        AiProperties.Retry retry = properties.getRetry();
        if (!retry.isEnabled()) {
            return Retryer.NEVER_RETRY;
        }
        return new Retryer.Default(
                retry.getPeriod().toMillis(),
                retry.getMaxPeriod().toMillis(),
                retry.getMaxAttempts());
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
