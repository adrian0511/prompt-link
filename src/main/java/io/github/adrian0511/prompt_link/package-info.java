/**
 * Cliente Java para consumir IA generativa a través de OpenRouter, con auto-configuración para
 * Spring Boot.
 *
 * <p>Añadir la dependencia y configurar {@code ai.api-key} basta: la librería registra sus beans
 * sola, sin necesidad de {@code @EnableFeignClients}. A partir de ahí, todo el uso normal pasa por
 * {@link io.github.adrian0511.prompt_link.service.AiService}.
 *
 * <pre>{@code
 * AiResponse respuesta = aiService.generate("¿Qué es un record en Java?");
 * String texto = respuesta.getContent();
 * }</pre>
 *
 * <p>Los fallos se comunican siempre como
 * {@link io.github.adrian0511.prompt_link.exceptions.AiClientException}, cuyo {@code statusCode}
 * distingue un error de la API de uno de red, de una respuesta inservible o de una configuración
 * incompleta.
 *
 * <h2>Organización</h2>
 *
 * <ul>
 *   <li>{@link io.github.adrian0511.prompt_link.service} – la API pública de la librería.
 *   <li>{@link io.github.adrian0511.prompt_link.config} – auto-configuración y propiedades.
 *   <li>{@link io.github.adrian0511.prompt_link.client} – el cliente HTTP de OpenRouter.
 *   <li>{@link io.github.adrian0511.prompt_link.dto} – los cuerpos de petición y respuesta.
 *   <li>{@link io.github.adrian0511.prompt_link.exceptions} – el modelo de errores.
 * </ul>
 */
package io.github.adrian0511.prompt_link;
