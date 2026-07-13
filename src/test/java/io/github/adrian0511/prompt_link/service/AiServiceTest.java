package io.github.adrian0511.prompt_link.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import feign.Request;
import feign.RetryableException;
import io.github.adrian0511.prompt_link.client.AiClient;
import io.github.adrian0511.prompt_link.config.AiProperties;
import io.github.adrian0511.prompt_link.dto.AiResponse;
import io.github.adrian0511.prompt_link.dto.Message;
import io.github.adrian0511.prompt_link.dto.OpenRouterRequest;
import io.github.adrian0511.prompt_link.dto.OpenRouterResponse;
import io.github.adrian0511.prompt_link.exceptions.AiClientException;

class AiServiceTest {

    private AiClient aiClient;
    private AiProperties properties;
    private AiService service;

    @BeforeEach
    void setUp() {
        aiClient = mock(AiClient.class);
        properties = new AiProperties();
        properties.setApiKey("test-key");
        service = new AiService(aiClient, properties);
    }

    @Test
    void devuelveElContenidoDelPrimerChoice() {
        when(aiClient.chatCompletion(any())).thenReturn(responseWith("hola"));

        AiResponse response = service.generate("¿qué tal?");

        assertThat(response.getContent()).isEqualTo("hola");
    }

    @Test
    void construyeLaPeticionConLasPropiedadesConfiguradas() {
        properties.setModel("anthropic/claude-sonnet-4");
        properties.setMaxTokens(128);
        properties.setTemperature(0.3);
        when(aiClient.chatCompletion(any())).thenReturn(responseWith("ok"));

        service.generate("eres un poeta", "escribe un haiku");

        ArgumentCaptor<OpenRouterRequest> captor = ArgumentCaptor.forClass(OpenRouterRequest.class);
        org.mockito.Mockito.verify(aiClient).chatCompletion(captor.capture());
        OpenRouterRequest request = captor.getValue();

        assertThat(request.getModel()).isEqualTo("anthropic/claude-sonnet-4");
        assertThat(request.getMaxTokens()).isEqualTo(128);
        assertThat(request.getTemperature()).isEqualTo(0.3);
        assertThat(request.getMessages()).extracting(Message::getRole, Message::getContent)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("system", "eres un poeta"),
                        org.assertj.core.groups.Tuple.tuple("user", "escribe un haiku"));
    }

    /**
     * Regresión: AiErrorDecoder ya construye una AiClientException con el status y el cuerpo
     * reales. Antes, AiService la re-envolvía en el catch genérico y todos los errores HTTP
     * llegaban al usuario como statusCode -1 y errorBody null, que es exactamente el código que la
     * documentación reserva para los errores de red.
     */
    @Test
    void preservaElStatusYElCuerpoDeLosErroresHttp() {
        when(aiClient.chatCompletion(any()))
                .thenThrow(new AiClientException(
                        "Error llamando a la API de IA: 429", 429, "{\"error\":\"rate limit\"}"));

        assertThatExceptionOfType(AiClientException.class)
                .isThrownBy(() -> service.generate("hola"))
                .satisfies(e -> {
                    assertThat(e.getStatusCode()).isEqualTo(429);
                    assertThat(e.getErrorBody()).isEqualTo("{\"error\":\"rate limit\"}");
                    assertThat(e.isHttpError()).isTrue();
                });
    }

    @Test
    void mapeaLosErroresDeRedANetworkError() {
        when(aiClient.chatCompletion(any())).thenThrow(networkFailure());

        assertThatExceptionOfType(AiClientException.class)
                .isThrownBy(() -> service.generate("hola"))
                .satisfies(e -> {
                    assertThat(e.getStatusCode()).isEqualTo(AiClientException.NETWORK_ERROR);
                    assertThat(e.isHttpError()).isFalse();
                    assertThat(e.getCause()).isInstanceOf(RetryableException.class);
                });
    }

    @Test
    void rechazaUnaRespuestaSinChoices() {
        OpenRouterResponse empty = new OpenRouterResponse();
        empty.setChoices(List.of());
        when(aiClient.chatCompletion(any())).thenReturn(empty);

        assertThatExceptionOfType(AiClientException.class)
                .isThrownBy(() -> service.generate("hola"))
                .satisfies(e -> assertThat(e.getStatusCode())
                        .isEqualTo(AiClientException.INVALID_RESPONSE));
    }

    @Test
    void rechazaUnChoiceSinContenido() {
        OpenRouterResponse response = new OpenRouterResponse();
        OpenRouterResponse.Choice choice = new OpenRouterResponse.Choice();
        choice.setMessage(new Message("assistant", null));
        response.setChoices(List.of(choice));
        when(aiClient.chatCompletion(any())).thenReturn(response);

        assertThatExceptionOfType(AiClientException.class)
                .isThrownBy(() -> service.generate("hola"))
                .satisfies(e -> assertThat(e.getStatusCode())
                        .isEqualTo(AiClientException.INVALID_RESPONSE));
    }

    @Test
    void fallaConMensajeClaroSiFaltaLaApiKey() {
        properties.setApiKey(null);

        assertThatExceptionOfType(AiClientException.class)
                .isThrownBy(() -> service.generate("hola"))
                .satisfies(e -> {
                    assertThat(e.getStatusCode())
                            .isEqualTo(AiClientException.CONFIGURATION_ERROR);
                    assertThat(e).hasMessageContaining("ai.api-key");
                });
    }

    @Test
    void rechazaUnaConversacionVacia() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.generate(List.of()));
    }

    private static OpenRouterResponse responseWith(String content) {
        OpenRouterResponse.Choice choice = new OpenRouterResponse.Choice();
        choice.setMessage(new Message("assistant", content));
        OpenRouterResponse response = new OpenRouterResponse();
        response.setChoices(List.of(choice));
        return response;
    }

    private static RetryableException networkFailure() {
        Request request = Request.create(
                Request.HttpMethod.POST,
                "https://openrouter.ai/api/v1/chat/completions",
                Map.of(),
                null,
                StandardCharsets.UTF_8,
                null);
        return new RetryableException(
                -1,
                "Connection refused",
                Request.HttpMethod.POST,
                new IOException("Connection refused"),
                (Long) null,
                request);
    }

}
