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

class AiClientAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    HttpMessageConvertersAutoConfiguration.class,
                    FeignAutoConfiguration.class,
                    AiClientAutoConfiguration.class))
            .withPropertyValues("ai.api-key=test-key");

    @Test
    void registraElClienteYElServicio() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(AiService.class);
            assertThat(context).hasSingleBean(AiProperties.class);
            assertThat(context).hasSingleBean(AiClient.class);
        });
    }

    /**
     * Regresión del fallo de seguridad: el interceptor que añade la cabecera Authorization y el
     * error decoder deben vivir únicamente en el contexto hijo del cliente Feign de OpenRouter.
     *
     * <p>Si se declaran en la autoconfiguración acaban en el contexto principal, y Feign resuelve
     * interceptores y error decoders mirando también a los contextos ancestros: se aplicarían a
     * TODOS los clientes Feign de la aplicación que use la librería, enviando la API key de
     * OpenRouter a cualquier otro servicio al que esa aplicación llame.
     */
    @Test
    void noFiltraElInterceptorNiElErrorDecoderAlContextoPrincipal() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(RequestInterceptor.class);
            assertThat(context).doesNotHaveBean(ErrorDecoder.class);
        });
    }

    @Test
    void aplicaLosValoresPorDefecto() {
        runner.run(context -> {
            AiProperties properties = context.getBean(AiProperties.class);
            assertThat(properties.getModel()).isEqualTo("openai/gpt-4o-mini");
            assertThat(properties.getUrl()).isEqualTo("https://openrouter.ai/api/v1");
            assertThat(properties.getMaxTokens()).isEqualTo(5000);
            assertThat(properties.getTemperature()).isNull();
            assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(60));
        });
    }

    @Test
    void enlazaLasPropiedadesDeConfiguracion() {
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
    void permiteSobrescribirElServicio() {
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
