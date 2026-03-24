package io.github.adrian0511.prompt_link.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import io.github.adrian0511.prompt_link.config.AiClientAutoConfiguration;
import io.github.adrian0511.prompt_link.dto.OpenRouterRequest;
import io.github.adrian0511.prompt_link.dto.OpenRouterResponse;

@FeignClient(name = "openrouter", url = "${ai.url:https://openrouter.ai/api/v1}", configuration = AiClientAutoConfiguration.class)
public interface AiClient {

    @PostMapping("/chat/completions")
    OpenRouterResponse chatCompletion(@RequestBody OpenRouterRequest request);

}
