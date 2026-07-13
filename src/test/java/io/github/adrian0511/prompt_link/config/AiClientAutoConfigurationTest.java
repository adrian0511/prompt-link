package io.github.adrian0511.prompt_link.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import feign.RequestInterceptor;
import feign.codec.ErrorDecoder;
import io.github.adrian0511.prompt_link.client.AiClient;
import io.github.adrian0511.prompt_link.service.AiService;

/**
 * Checks which beans end up in the main context of the application using this library: that the
 * client and the service are there, that the properties are bound, that the service can be
 * overridden, and above all that Feign's beans are <em>not</em> there.
 */
class AiClientAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    HttpMessageConvertersAutoConfiguration.class,
                    FeignAutoConfiguration.class,
                    AiClientAutoConfiguration.class))
            .withPropertyValues("ai.api-key=test-key");

    @Test
    void registersTheClientAndTheService() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(AiService.class);
            assertThat(context).hasSingleBean(AiProperties.class);
            assertThat(context).hasSingleBean(AiClient.class);
        });
    }

    /**
     * Regression for the security bug: the interceptor that adds the Authorization header and the
     * error decoder must live only in the child context of the OpenRouter Feign client.
     *
     * <p>Declared in the auto-configuration they end up in the main context, and Feign resolves
     * interceptors and error decoders by also looking at ancestor contexts: they would apply to EVERY
     * Feign client of the application using this library, sending the OpenRouter API key to any other
     * service that application happens to call.
     */
    @Test
    void doesNotLeakTheInterceptorOrTheErrorDecoderIntoTheMainContext() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(RequestInterceptor.class);
            assertThat(context).doesNotHaveBean(ErrorDecoder.class);
        });
    }

    @Test
    void appliesTheDefaultValues() {
        runner.run(context -> {
            AiProperties properties = context.getBean(AiProperties.class);
            assertThat(properties.getModel()).isEqualTo("openai/gpt-4o-mini");
            assertThat(properties.getUrl()).isEqualTo("https://openrouter.ai/api/v1");
            assertThat(properties.getMaxTokens()).isEqualTo(5000);
            assertThat(properties.getTemperature()).isNull();
            assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(60));
            assertThat(properties.getRetry().isEnabled()).isFalse();
        });
    }

    @Test
    void bindsTheConfigurationProperties() {
        runner.withPropertyValues(
                "ai.model=anthropic/claude-sonnet-4",
                "ai.max-tokens=128",
                "ai.temperature=0.7",
                "ai.read-timeout=90s")
                .run(context -> {
                    AiProperties properties = context.getBean(AiProperties.class);
                    assertThat(properties.getApiKey()).isEqualTo("test-key");
                    assertThat(properties.getModel()).isEqualTo("anthropic/claude-sonnet-4");
                    assertThat(properties.getMaxTokens()).isEqualTo(128);
                    assertThat(properties.getTemperature()).isEqualTo(0.7);
                    assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(90));
                });
    }

    @Test
    void allowsOverridingTheService() {
        runner.withUserConfiguration(CustomAiServiceConfig.class)
                .run(context -> assertThat(context).hasSingleBean(AiService.class)
                        .getBean(AiService.class)
                        .isSameAs(CustomAiServiceConfig.CUSTOM));
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomAiServiceConfig {

        static final AiService CUSTOM = new AiService(null, new AiProperties());

        @Bean
        AiService aiService() {
            return CUSTOM;
        }
    }

}
