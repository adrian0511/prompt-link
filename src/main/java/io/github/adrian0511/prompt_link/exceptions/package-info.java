/**
 * The library's error model.
 *
 * <p>Every failure reaches the caller as an
 * {@link io.github.adrian0511.prompt_link.exceptions.AiClientException}, whether it came from an
 * error response of the API or from a call that never completed. Its {@code statusCode} tells the two
 * apart, and callers never need to know any of Feign's types.
 *
 * <p>{@link io.github.adrian0511.prompt_link.exceptions.AiErrorDecoder} performs that translation for
 * the blocking client, and also marks rate limits and server errors as retryable.
 */
package io.github.adrian0511.prompt_link.exceptions;
