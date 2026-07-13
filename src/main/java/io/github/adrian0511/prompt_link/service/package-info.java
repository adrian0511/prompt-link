/**
 * The library's public API.
 *
 * <p>{@link io.github.adrian0511.prompt_link.service.AiService} is the only thing you need to inject:
 * it validates the configuration, builds the request from the {@code ai.*} properties, calls
 * OpenRouter and checks that the answer is usable before returning it.
 *
 * <p>{@link io.github.adrian0511.prompt_link.service.ReactiveAiService} is its reactive counterpart,
 * registered only when WebFlux is on the classpath. It adds streaming: emitting the tokens as the
 * model generates them.
 */
package io.github.adrian0511.prompt_link.service;
