package io.github.adrian0511.prompt_link.exceptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import feign.Response;
import feign.RetryableException;
import feign.codec.ErrorDecoder;

/**
 * Translates OpenRouter's error responses into {@link AiClientException}, preserving the HTTP status
 * and the body. Without this, Feign would throw a generic {@code FeignException} and callers would
 * have to depend on Feign's types just to find out what failed.
 *
 * <p>Rate limits (429) and server errors (5xx) are additionally wrapped in a
 * {@link RetryableException}, which is how Feign signals "this may be retried". Whether it actually
 * is retried is up to the configured {@code Retryer}, not this decoder: with the default
 * configuration nothing is retried and the exception propagates immediately. Either way the original
 * {@link AiClientException} travels along as the cause, so the caller always sees the same error
 * type with its status and body intact.
 *
 * <p>Registered in {@code AiFeignConfiguration}, so it only affects the OpenRouter client and not
 * any other Feign client in the application.
 */
public class AiErrorDecoder implements ErrorDecoder {

    private static final Logger log = LoggerFactory.getLogger(AiErrorDecoder.class);

    private static final int TOO_MANY_REQUESTS = 429;

    @Override
    public Exception decode(String methodKey, Response response) {
        AiClientException error = new AiClientException(
                "Error calling the AI API: " + response.status(),
                response.status(),
                readBody(response));

        if (!isRetryable(response.status())) {
            return error;
        }

        return new RetryableException(
                response.status(),
                error.getMessage(),
                response.request().httpMethod(),
                error,
                retryAt(response),
                response.request());
    }

    /**
     * A 429 means the request was never even processed, and a 5xx means the failure is on
     * OpenRouter's side. Everything else (401, 402, 404…) depends on the request or the account:
     * repeating it would produce exactly the same error.
     */
    private static boolean isRetryable(int status) {
        return status == TOO_MANY_REQUESTS || status >= 500;
    }

    /**
     * OpenRouter uses Retry-After to say how many seconds to wait. Feign expects that as the
     * absolute instant from which to retry.
     */
    private Long retryAt(Response response) {
        String retryAfter = firstHeader(response.headers(), "Retry-After");
        if (retryAfter == null) {
            return null;
        }
        try {
            return System.currentTimeMillis() + Long.parseLong(retryAfter.trim()) * 1000L;
        } catch (NumberFormatException e) {
            // The header also accepts an HTTP date. We do not parse it: OpenRouter rarely uses that
            // form, and the Retryer has its own backoff to fall back on.
            log.debug("Non-numeric Retry-After header, ignoring it: {}", retryAfter);
            return null;
        }
    }

    private static String firstHeader(Map<String, Collection<String>> headers, String name) {
        Collection<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.iterator().next();
    }

    /**
     * The body is the most valuable part of the error (OpenRouter explains there whether it is the
     * key, the credits or the rate limit), but failing to read it must not hide the original HTTP
     * error: if it fails, it is logged and an empty body is returned.
     */
    private String readBody(Response response) {
        if (response.body() == null) {
            return "";
        }
        try (var input = response.body().asInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not read the body of the AI API's error response", e);
            return "";
        }
    }

}
