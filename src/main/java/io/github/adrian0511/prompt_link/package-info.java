/**
 * A Java client for generative AI through OpenRouter, with Spring Boot auto-configuration.
 *
 * <p>Adding the dependency and setting {@code ai.api-key} is enough: the library registers its own
 * beans, with no need for {@code @EnableFeignClients}. From there, everyday use goes through
 * {@link io.github.adrian0511.prompt_link.service.AiService}.
 *
 * <pre>{@code
 * AiResponse answer = aiService.generate("What is a record in Java?");
 * String text = answer.getContent();
 * }</pre>
 *
 * <p>Applications on WebFlux also get
 * {@link io.github.adrian0511.prompt_link.service.ReactiveAiService}, which can stream the tokens as
 * the model generates them.
 *
 * <p>Failures always surface as an
 * {@link io.github.adrian0511.prompt_link.exceptions.AiClientException}, whose {@code statusCode}
 * separates an API error from a network error, an unusable response or incomplete configuration.
 *
 * <h2>Layout</h2>
 *
 * <ul>
 *   <li>{@link io.github.adrian0511.prompt_link.service} – the library's public API.
 *   <li>{@link io.github.adrian0511.prompt_link.config} – auto-configuration and properties.
 *   <li>{@link io.github.adrian0511.prompt_link.client} – the OpenRouter HTTP client.
 *   <li>{@link io.github.adrian0511.prompt_link.dto} – the request and response bodies.
 *   <li>{@link io.github.adrian0511.prompt_link.exceptions} – the error model.
 * </ul>
 */
package io.github.adrian0511.prompt_link;
