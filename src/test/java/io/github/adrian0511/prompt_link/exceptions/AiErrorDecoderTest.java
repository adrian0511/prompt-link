package io.github.adrian0511.prompt_link.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import feign.Request;
import feign.Response;

class AiErrorDecoderTest {

    private final AiErrorDecoder decoder = new AiErrorDecoder();

    @Test
    void conservaElStatusYElCuerpoDelError() {
        Exception decoded = decoder.decode("AiClient#chatCompletion(OpenRouterRequest)",
                responseWith(429, "{\"error\":\"rate limit exceeded\"}"));

        assertThat(decoded).isInstanceOf(AiClientException.class);
        AiClientException exception = (AiClientException) decoded;
        assertThat(exception.getStatusCode()).isEqualTo(429);
        assertThat(exception.getErrorBody()).isEqualTo("{\"error\":\"rate limit exceeded\"}");
        assertThat(exception.isHttpError()).isTrue();
    }

    @Test
    void toleraUnaRespuestaSinCuerpo() {
        Exception decoded = decoder.decode("AiClient#chatCompletion(OpenRouterRequest)",
                responseWith(401, null));

        AiClientException exception = (AiClientException) decoded;
        assertThat(exception.getStatusCode()).isEqualTo(401);
        assertThat(exception.getErrorBody()).isEmpty();
    }

    private static Response responseWith(int status, String body) {
        Request request = Request.create(
                Request.HttpMethod.POST,
                "https://openrouter.ai/api/v1/chat/completions",
                Map.of(),
                null,
                StandardCharsets.UTF_8,
                null);

        Response.Builder builder = Response.builder()
                .status(status)
                .reason("error")
                .request(request)
                .headers(Map.of());

        return body == null ? builder.build() : builder.body(body, StandardCharsets.UTF_8).build();
    }

}
