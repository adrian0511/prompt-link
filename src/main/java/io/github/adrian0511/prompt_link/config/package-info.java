/**
 * Auto-configuración y propiedades de la librería.
 *
 * <p>La separación entre las dos clases de configuración es deliberada y conviene respetarla:
 *
 * <ul>
 *   <li>{@link io.github.adrian0511.prompt_link.config.AiClientAutoConfiguration} se carga en el
 *       contexto principal de la aplicación y registra el cliente y el servicio.
 *   <li>{@link io.github.adrian0511.prompt_link.config.AiFeignConfiguration} contiene los beans
 *       propios de Feign (autenticación, traducción de errores, timeouts, reintentos) y Spring
 *       Cloud la carga <strong>solo</strong> en el contexto del cliente de OpenRouter.
 * </ul>
 *
 * <p>Declarar un {@code RequestInterceptor} o un {@code ErrorDecoder} en la auto-configuración los
 * dejaría en el contexto principal, y Feign los heredaría en todos los clientes de la aplicación:
 * la cabecera {@code Authorization} con la API key de OpenRouter acabaría viajando a cualquier otro
 * servicio al que esa aplicación llamase por Feign. Por eso viven separados.
 */
package io.github.adrian0511.prompt_link.config;
