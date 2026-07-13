package io.github.adrian0511.prompt_link.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import io.github.adrian0511.prompt_link.config.AiFeignConfiguration;
import io.github.adrian0511.prompt_link.dto.OpenRouterRequest;
import io.github.adrian0511.prompt_link.dto.OpenRouterResponse;

/**
 * Cliente HTTP de la API de OpenRouter.
 *
 * <p>Es un detalle de implementación: normalmente querrás inyectar
 * {@link io.github.adrian0511.prompt_link.service.AiService}, que añade la validación de la
 * configuración, la comprobación de la respuesta y el manejo de errores.
 *
 * <p>La autenticación, los timeouts y la traducción de errores los aporta
 * {@link AiFeignConfiguration}, que Spring Cloud carga solo en el contexto de este cliente.
 */
@FeignClient(name = "openrouter", url = "${ai.url:https://openrouter.ai/api/v1}", configuration = AiFeignConfiguration.class)
public interface AiClient {

    @PostMapping("/chat/completions")
    OpenRouterResponse chatCompletion(@RequestBody OpenRouterRequest request);

}
