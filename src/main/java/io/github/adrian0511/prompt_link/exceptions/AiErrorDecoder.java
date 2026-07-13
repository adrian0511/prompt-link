package io.github.adrian0511.prompt_link.exceptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import feign.Response;
import feign.RetryableException;
import feign.codec.ErrorDecoder;

/**
 * Traduce las respuestas de error de OpenRouter a {@link AiClientException}, conservando el código
 * HTTP y el cuerpo. Sin esto, Feign lanzaría una {@code FeignException} genérica y el llamante
 * tendría que depender de tipos de Feign para saber qué falló.
 *
 * <p>Los rate limits (429) y los errores del servidor (5xx) se envuelven además en una
 * {@link RetryableException}, que es la forma que tiene Feign de señalar "esto se puede reintentar".
 * Quien decide si se reintenta de verdad es el {@code Retryer} configurado, no este decoder: con la
 * configuración por defecto no se reintenta nada y la excepción se propaga en el acto. En ambos
 * casos la {@link AiClientException} original viaja como causa, así que el llamante recibe siempre
 * el mismo tipo de error con su status y su cuerpo intactos.
 *
 * <p>Se registra en {@code AiFeignConfiguration}, de modo que solo afecta al cliente de OpenRouter
 * y no a otros clientes Feign de la aplicación.
 */
public class AiErrorDecoder implements ErrorDecoder {

    private static final Logger log = LoggerFactory.getLogger(AiErrorDecoder.class);

    private static final int TOO_MANY_REQUESTS = 429;

    @Override
    public Exception decode(String methodKey, Response response) {
        AiClientException error = new AiClientException(
                "Error llamando a la API de IA: " + response.status(),
                response.status(),
                readBody(response));

        if (!esReintentable(response.status())) {
            return error;
        }

        return new RetryableException(
                response.status(),
                error.getMessage(),
                response.request().httpMethod(),
                error,
                reintentarA(response),
                response.request());
    }

    /**
     * Un 429 significa que la petición ni siquiera se procesó, y un 5xx que el fallo es del lado de
     * OpenRouter. El resto (401, 402, 404…) depende de la petición o de la cuenta: repetirla daría
     * exactamente el mismo error.
     */
    private static boolean esReintentable(int status) {
        return status == TOO_MANY_REQUESTS || status >= 500;
    }

    /**
     * OpenRouter indica en Retry-After cuántos segundos esperar. Feign espera ese dato como el
     * instante absoluto a partir del cual reintentar.
     */
    private Long reintentarA(Response response) {
        String retryAfter = primeraCabecera(response.headers(), "Retry-After");
        if (retryAfter == null) {
            return null;
        }
        try {
            return System.currentTimeMillis() + Long.parseLong(retryAfter.trim()) * 1000L;
        } catch (NumberFormatException e) {
            // La cabecera admite también una fecha HTTP. No la interpretamos: es raro que
            // OpenRouter la use, y el Retryer tiene su propio backoff al que caer.
            log.debug("Cabecera Retry-After no numérica, se ignora: {}", retryAfter);
            return null;
        }
    }

    private static String primeraCabecera(Map<String, Collection<String>> headers, String nombre) {
        Collection<String> valores = headers.get(nombre);
        return valores == null || valores.isEmpty() ? null : valores.iterator().next();
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
