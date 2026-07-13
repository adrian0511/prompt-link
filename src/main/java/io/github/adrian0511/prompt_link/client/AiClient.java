package io.github.adrian0511.prompt_link.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import io.github.adrian0511.prompt_link.config.AiFeignConfiguration;
import io.github.adrian0511.prompt_link.dto.OpenRouterRequest;
import io.github.adrian0511.prompt_link.dto.OpenRouterResponse;

/**
 * HTTP client for the OpenRouter API.
 *
 * <p>This is an implementation detail: you normally want to inject
 * {@link io.github.adrian0511.prompt_link.service.AiService}, which adds configuration validation,
 * response checking and error handling on top.
 *
 * <p>Authentication, timeouts and error translation come from {@link AiFeignConfiguration}, which
 * Spring Cloud loads only in this client's context.
 */
@FeignClient(name = "openrouter", url = "${ai.url:https://openrouter.ai/api/v1}", configuration = AiFeignConfiguration.class)
public interface AiClient {

    @PostMapping("/chat/completions")
    OpenRouterResponse chatCompletion(@RequestBody OpenRouterRequest request);

}
