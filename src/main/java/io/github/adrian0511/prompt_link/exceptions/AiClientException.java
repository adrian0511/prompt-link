package io.github.adrian0511.prompt_link.exceptions;

/**
 * The only failure this library propagates to the caller. The {@link #getStatusCode() statusCode}
 * tells you what went wrong:
 *
 * <ul>
 *   <li><b>greater than 0</b> – an HTTP error from the API (401, 402, 429, 5xx…).
 *       {@link #getErrorBody()} holds the response body, which usually explains why.
 *   <li>{@link #NETWORK_ERROR} – the call never completed.
 *   <li>{@link #INVALID_RESPONSE} – the API answered successfully but with nothing usable.
 *   <li>{@link #CONFIGURATION_ERROR} – required configuration is missing.
 *   <li>{@link #STREAM_ERROR} – the API failed midway through a stream.
 * </ul>
 *
 * <p>The distinction matters when deciding how to react: an HTTP error is usually the request's or
 * the account's fault (bad key, no credits, rate limit) and repeating the call will not help, while
 * a {@link #NETWORK_ERROR} is a reasonable candidate for a retry.
 */
public class AiClientException extends RuntimeException {

    /** The call never completed: timeout, DNS failure, connection refused. */
    public static final int NETWORK_ERROR = -1;

    /** The API answered successfully but the body is unusable: no choices, or no content. */
    public static final int INVALID_RESPONSE = -2;

    /** The library is misconfigured: {@code ai.api-key} is missing. */
    public static final int CONFIGURATION_ERROR = -3;

    /**
     * The API failed <em>midway through a stream</em>, after having already answered 200, and
     * without giving a numeric HTTP code (it sends things like {@code "server_error"}).
     *
     * <p>Treated as transient: if the failure arrives before the first token, the call is retried.
     * When OpenRouter does give a numeric code, that code is used with its usual meaning, so a 402
     * midway through a stream is still not retried.
     */
    public static final int STREAM_ERROR = -4;

    private final int statusCode;
    private final String errorBody;

    public AiClientException(String message, int statusCode, String errorBody) {
        this(message, statusCode, errorBody, null);
    }

    public AiClientException(String message, int statusCode, String errorBody, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.errorBody = errorBody;
    }

    /**
     * The HTTP status returned by the API, or one of the negative constants of this class when the
     * failure happened before there was a response.
     */
    public int getStatusCode() {
        return this.statusCode;
    }

    /**
     * The raw body of the API's error response, or {@code null} when the failure never produced
     * one.
     */
    public String getErrorBody() {
        return this.errorBody;
    }

    /** {@code true} if the failure came from an HTTP error response of the API (4xx/5xx). */
    public boolean isHttpError() {
        return this.statusCode > 0;
    }
}
