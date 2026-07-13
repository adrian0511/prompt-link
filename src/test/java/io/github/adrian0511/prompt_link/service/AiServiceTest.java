package io.github.adrian0511.prompt_link.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

/**
 * Covers the service's logic with a mocked client: how the request is built from the properties and,
 * above all, how each kind of failure is translated.
 *
 * <p>The important one is {@link #preservesTheStatusAndBodyOfHttpErrors()}: it pins down that an API
 * error arrives with its real status instead of being downgraded to a network error.
 */
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
    void returnsTheContentOfTheFirstChoice() {
        when(aiClient.chatCompletion(any())).thenReturn(responseWith("hello"));

        AiResponse response = service.generate("how are you?");

        assertThat(response.getContent()).isEqualTo("hello");
    }

    @Test
    void buildsTheRequestFromTheConfiguredProperties() {
        properties.setModel("anthropic/claude-sonnet-4");
        properties.setMaxTokens(128);
        properties.setTemperature(0.3);
        when(aiClient.chatCompletion(any())).thenReturn(responseWith("ok"));

        service.generate("you are a poet", "write a haiku");

        ArgumentCaptor<OpenRouterRequest> captor = ArgumentCaptor.forClass(OpenRouterRequest.class);
        verify(aiClient).chatCompletion(captor.capture());
        OpenRouterRequest request = captor.getValue();

        assertThat(request.getModel()).isEqualTo("anthropic/claude-sonnet-4");
        assertThat(request.getMaxTokens()).isEqualTo(128);
        assertThat(request.getTemperature()).isEqualTo(0.3);
        assertThat(request.getMessages()).extracting(Message::getRole, Message::getContent)
                .containsExactly(
                        tuple("system", "you are a poet"),
                        tuple("user", "write a haiku"));
    }

    /**
     * Regression: AiErrorDecoder already builds an AiClientException with the real status and body.
     * AiService used to re-wrap it in its generic catch, so every HTTP error reached the caller as
     * statusCode -1 with a null body — exactly the code the documentation reserves for network
     * failures.
     */
    @Test
    void preservesTheStatusAndBodyOfHttpErrors() {
        when(aiClient.chatCompletion(any()))
                .thenThrow(new AiClientException(
                        "Error calling the AI API: 429", 429, "{\"error\":\"rate limit\"}"));

        assertThatExceptionOfType(AiClientException.class)
                .isThrownBy(() -> service.generate("hello"))
                .satisfies(e -> {
                    assertThat(e.getStatusCode()).isEqualTo(429);
                    assertThat(e.getErrorBody()).isEqualTo("{\"error\":\"rate limit\"}");
                    assertThat(e.isHttpError()).isTrue();
                });
    }

    @Test
    void mapsNetworkFailuresToNetworkError() {
        when(aiClient.chatCompletion(any())).thenThrow(networkFailure());

        assertThatExceptionOfType(AiClientException.class)
                .isThrownBy(() -> service.generate("hello"))
                .satisfies(e -> {
                    assertThat(e.getStatusCode()).isEqualTo(AiClientException.NETWORK_ERROR);
                    assertThat(e.isHttpError()).isFalse();
                    assertThat(e.getCause()).isInstanceOf(RetryableException.class);
                });
    }

    @Test
    void rejectsAResponseWithNoChoices() {
        OpenRouterResponse empty = new OpenRouterResponse();
        empty.setChoices(List.of());
        when(aiClient.chatCompletion(any())).thenReturn(empty);

        assertThatExceptionOfType(AiClientException.class)
                .isThrownBy(() -> service.generate("hello"))
                .satisfies(e -> assertThat(e.getStatusCode())
                        .isEqualTo(AiClientException.INVALID_RESPONSE));
    }

    @Test
    void rejectsAChoiceWithNoContent() {
        OpenRouterResponse response = new OpenRouterResponse();
        OpenRouterResponse.Choice choice = new OpenRouterResponse.Choice();
        choice.setMessage(new Message("assistant", null));
        response.setChoices(List.of(choice));
        when(aiClient.chatCompletion(any())).thenReturn(response);

        assertThatExceptionOfType(AiClientException.class)
                .isThrownBy(() -> service.generate("hello"))
                .satisfies(e -> assertThat(e.getStatusCode())
                        .isEqualTo(AiClientException.INVALID_RESPONSE));
    }

    @Test
    void failsWithAClearMessageWhenTheApiKeyIsMissing() {
        properties.setApiKey(null);

        assertThatExceptionOfType(AiClientException.class)
                .isThrownBy(() -> service.generate("hello"))
                .satisfies(e -> {
                    assertThat(e.getStatusCode())
                            .isEqualTo(AiClientException.CONFIGURATION_ERROR);
                    assertThat(e).hasMessageContaining("ai.api-key");
                });
    }

    @Test
    void rejectsAnEmptyConversation() {
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
