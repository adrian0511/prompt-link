/**
 * El modelo de errores de la librería.
 *
 * <p>Todo fallo llega al llamante como una
 * {@link io.github.adrian0511.prompt_link.exceptions.AiClientException}, tanto si viene de una
 * respuesta de error de la API como si la llamada no llegó a completarse. Su {@code statusCode}
 * indica cuál de los dos casos es, y el llamante no necesita conocer ningún tipo de Feign.
 *
 * <p>{@link io.github.adrian0511.prompt_link.exceptions.AiErrorDecoder} es quien hace esa
 * traducción, y además marca los rate limits y los errores del servidor como reintentables.
 */
package io.github.adrian0511.prompt_link.exceptions;
