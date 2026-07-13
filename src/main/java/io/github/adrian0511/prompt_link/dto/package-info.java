/**
 * The request and response bodies exchanged with OpenRouter.
 *
 * <p>{@link io.github.adrian0511.prompt_link.dto.OpenRouterRequest} and
 * {@link io.github.adrian0511.prompt_link.dto.OpenRouterResponse} mirror the API's wire format, and
 * you should only touch them if you customize the client. Everyday use involves two types:
 * {@link io.github.adrian0511.prompt_link.dto.Message}, which you build a conversation from, and
 * {@link io.github.adrian0511.prompt_link.dto.AiResponse}, which shields the caller from OpenRouter's
 * format and exposes only the generated text.
 */
package io.github.adrian0511.prompt_link.dto;
