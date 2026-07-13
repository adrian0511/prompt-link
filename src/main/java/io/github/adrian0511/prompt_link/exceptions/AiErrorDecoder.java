package io.github.adrian0511.prompt_link.exceptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import feign.Response;
import feign.codec.ErrorDecoder;

/**
 * Traduce las respuestas de error de OpenRouter a {@link AiClientException}, conservando el código
 * HTTP y el cuerpo. Sin esto, Feign lanzaría una {@code FeignException} genérica y el llamante
 * tendría que depender de tipos de Feign para saber qué falló.
 *
 * <p>Se registra en {@code AiFeignConfiguration}, de modo que solo afecta al cliente de OpenRouter
 * y no a otros clientes Feign de la aplicación.
 */
public class AiErrorDecoder implements ErrorDecoder {

    private static final Logger log = LoggerFactory.getLogger(AiErrorDecoder.class);

    @Override
    public Exception decode(String methodKey, Response response) {
        return new AiClientException(
                "Error llamando a la API de IA: " + response.status(),
                response.status(),
                readBody(response));
    }

    /**
     * El cuerpo es lo más valioso del error (OpenRouter explica ahí si es la clave, los créditos o
     * el rate limit), pero no poder leerlo no debe tapar el error HTTP original: si falla, se
     * registra y se devuelve vacío.
     */
    private String readBody(Response response) {
        if (response.body() == null) {
            return "";
        }
        try (var input = response.body().asInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("No se pudo leer el cuerpo de la respuesta de error de la API de IA", e);
            return "";
        }
    }

}
