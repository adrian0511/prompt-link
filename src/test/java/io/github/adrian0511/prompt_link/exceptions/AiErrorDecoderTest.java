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

/**
 * Covers the translation of OpenRouter's error responses: that the status and the body survive, and
 * that only what is actually worth retrying gets marked as retryable.
 */
class AiErrorDecoderTest {

    private final AiErrorDecoder decoder = new AiErrorDecoder();

    @Test
    void preservesTheStatusAndTheBodyOfTheError() {
        Exception decoded = decoder.decode("AiClient#chatCompletion(OpenRouterRequest)",
                responseWith(402, "{\"error\":\"insufficient credits\"}"));

        assertThat(decoded).isInstanceOf(AiClientException.class);
        AiClientException exception = (AiClientException) decoded;
        assertThat(exception.getStatusCode()).isEqualTo(402);
        assertThat(exception.getErrorBody()).isEqualTo("{\"error\":\"insufficient credits\"}");
        assertThat(exception.isHttpError()).isTrue();
    }

    @Test
    void toleratesAResponseWithNoBody() {
        Exception decoded = decoder.decode("AiClient#chatCompletion(OpenRouterRequest)",
                responseWith(401, null));

        AiClientException exception = (AiClientException) decoded;
        assertThat(exception.getStatusCode()).isEqualTo(401);
        assertThat(exception.getErrorBody()).isEmpty();
    }

    @Test
    void marksRateLimitsAndServerErrorsAsRetryable() {
        assertThat(decoder.decode("x", responseWith(429, "{}"))).isInstanceOf(RetryableException.class);
        assertThat(decoder.decode("x", responseWith(500, "{}"))).isInstanceOf(RetryableException.class);
        assertThat(decoder.decode("x", responseWith(503, "{}"))).isInstanceOf(RetryableException.class);
    }

    /** Repeating these would produce the exact same error: the request or the account is the problem. */
    @Test
    void doesNotMarkRequestErrorsAsRetryable() {
        assertThat(decoder.decode("x", responseWith(401, "{}"))).isExactlyInstanceOf(AiClientException.class);
        assertThat(decoder.decode("x", responseWith(402, "{}"))).isExactlyInstanceOf(AiClientException.class);
        assertThat(decoder.decode("x", responseWith(404, "{}"))).isExactlyInstanceOf(AiClientException.class);
    }

    /** Even when retryable, the typed error travels inside so that the caller loses nothing. */
    @Test
    void keepsTheTypedErrorAsTheCauseOfTheRetryableOne() {
        RetryableException retryable =
                (RetryableException) decoder.decode("x", responseWith(429, "{\"error\":\"slow down\"}"));

        assertThat(retryable.getCause())
                .isInstanceOf(AiClientException.class)
                .satisfies(cause -> {
                    AiClientException error = (AiClientException) cause;
                    assertThat(error.getStatusCode()).isEqualTo(429);
                    assertThat(error.getErrorBody()).contains("slow down");
                });
    }

    @Test
    void honoursTheRetryAfterHeader() {
        Response response = responseWith(429, "{}", Map.of("Retry-After", List.of("2")));

        RetryableException retryable = (RetryableException) decoder.decode("x", response);

        // Feign reads it as the absolute instant from which to retry.
        long inTwoSeconds = System.currentTimeMillis() + 2_000L;
        assertThat(retryable.retryAfter()).isBetween(inTwoSeconds - 1_000L, inTwoSeconds + 1_000L);
    }

    @Test
    void withoutRetryAfterItLetsTheRetryerDecideTheBackoff() {
        RetryableException retryable = (RetryableException) decoder.decode("x", responseWith(429, "{}"));

        assertThat(retryable.retryAfter()).isNull();
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
