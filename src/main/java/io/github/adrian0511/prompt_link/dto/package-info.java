/**
 * Los cuerpos de petición y respuesta que se intercambian con OpenRouter.
 *
 * <p>{@link io.github.adrian0511.prompt_link.dto.OpenRouterRequest} y
 * {@link io.github.adrian0511.prompt_link.dto.OpenRouterResponse} reflejan el formato de la API tal
 * cual, y solo deberías tocarlos si personalizas el cliente. Del uso normal salen dos tipos:
 * {@link io.github.adrian0511.prompt_link.dto.Message}, con el que construyes una conversación, y
 * {@link io.github.adrian0511.prompt_link.dto.AiResponse}, que aísla al llamante del formato de
 * OpenRouter y solo expone el texto generado.
 */
package io.github.adrian0511.prompt_link.dto;
