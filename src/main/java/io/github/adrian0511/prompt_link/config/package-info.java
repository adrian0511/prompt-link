/**
 * Auto-configuration and properties.
 *
 * <p>The split between the configuration classes is deliberate and worth preserving:
 *
 * <ul>
 *   <li>{@link io.github.adrian0511.prompt_link.config.AiClientAutoConfiguration} is loaded in the
 *       application's main context and registers the client and the service.
 *   <li>{@link io.github.adrian0511.prompt_link.config.AiFeignConfiguration} holds Feign's own beans
 *       (authentication, error translation, timeouts, retries) and Spring Cloud loads it
 *       <strong>only</strong> in the OpenRouter client's context.
 *   <li>{@link io.github.adrian0511.prompt_link.config.ReactiveAiAutoConfiguration} registers the
 *       reactive service, and only when WebFlux is on the classpath.
 * </ul>
 *
 * <p>Declaring a {@code RequestInterceptor} or an {@code ErrorDecoder} in the auto-configuration
 * would leave them in the main context, and Feign would inherit them into every client of the
 * application: the Authorization header carrying the OpenRouter API key would end up travelling to
 * any other service that application called over Feign. That is why they live apart. The same care is
 * taken on the reactive side, where the {@code WebClient} is built by cloning the application's
 * builder rather than mutating it.
 */
package io.github.adrian0511.prompt_link.config;
