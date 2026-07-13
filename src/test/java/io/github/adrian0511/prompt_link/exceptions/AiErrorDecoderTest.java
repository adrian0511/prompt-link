package io.github.adrian0511.prompt_link.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import feign.Request;
import feign.Response;
import feign.RetryableException;

class AiErrorDecoderTest {

    private final AiErrorDecoder decoder = new AiErrorDecoder();

    @Test
    void conservaElStatusYElCuerpoDelError() {
        Exception decoded = decoder.decode("AiClient#chatCompletion(OpenRouterRequest)",
                responseWith(402, "{\"error\":\"insufficient credits\"}"));

        assertThat(decoded).isInstanceOf(AiClientException.class);
        AiClientException exception = (AiClientException) decoded;
        assertThat(exception.getStatusCode()).isEqualTo(402);
        assertThat(exception.getErrorBody()).isEqualTo("{\"error\":\"insufficient credits\"}");
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

    @Test
    void marcaLosRateLimitsYLosErroresDelServidorComoReintentables() {
        assertThat(decoder.decode("x", responseWith(429, "{}"))).isInstanceOf(RetryableException.class);
        assertThat(decoder.decode("x", responseWith(500, "{}"))).isInstanceOf(RetryableException.class);
        assertThat(decoder.decode("x", responseWith(503, "{}"))).isInstanceOf(RetryableException.class);
    }

    /** Repetirlos daría exactamente el mismo error: el problema es la petición o la cuenta. */
    @Test
    void noMarcaComoReintentablesLosErroresDeLaPeticion() {
        assertThat(decoder.decode("x", responseWith(401, "{}"))).isExactlyInstanceOf(AiClientException.class);
        assertThat(decoder.decode("x", responseWith(402, "{}"))).isExactlyInstanceOf(AiClientException.class);
        assertThat(decoder.decode("x", responseWith(404, "{}"))).isExactlyInstanceOf(AiClientException.class);
    }

    /** Aun siendo reintentable, el error tipado viaja dentro para que el llamante no pierda nada. */
    @Test
    void conservaElErrorTipadoComoCausaDeLoReintentable() {
        RetryableException reintentable =
                (RetryableException) decoder.decode("x", responseWith(429, "{\"error\":\"slow down\"}"));

        assertThat(reintentable.getCause())
                .isInstanceOf(AiClientException.class)
                .satisfies(causa -> {
                    AiClientException error = (AiClientException) causa;
                    assertThat(error.getStatusCode()).isEqualTo(429);
                    assertThat(error.getErrorBody()).contains("slow down");
                });
    }

    @Test
    void respetaLaCabeceraRetryAfter() {
        Response response = responseWith(429, "{}", Map.of("Retry-After", List.of("2")));

        RetryableException reintentable = (RetryableException) decoder.decode("x", response);

        // Feign lo interpreta como el instante absoluto a partir del cual reintentar.
        long dentroDeDosSegundos = System.currentTimeMillis() + 2_000L;
        assertThat(reintentable.retryAfter())
                .isBetween(dentroDeDosSegundos - 1_000L, dentroDeDosSegundos + 1_000L);
    }

    @Test
    void sinRetryAfterDejaQueDecidaElBackoffDelRetryer() {
        RetryableException reintentable = (RetryableException) decoder.decode("x", responseWith(429, "{}"));

        assertThat(reintentable.retryAfter()).isNull();
    }

    private static Response responseWith(int status, String body) {
        return responseWith(status, body, Map.of());
    }

    private static Response responseWith(int status, String body, Map<String, Collection<String>> headers) {
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
                .headers(headers);

        return body == null ? builder.build() : builder.body(body, StandardCharsets.UTF_8).build();
    }

}
