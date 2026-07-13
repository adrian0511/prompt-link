package io.github.adrian0511.prompt_link.service;

import java.util.List;

import org.springframework.util.StringUtils;

import feign.FeignException;
import io.github.adrian0511.prompt_link.client.AiClient;
import io.github.adrian0511.prompt_link.config.AiProperties;
import io.github.adrian0511.prompt_link.dto.AiResponse;
import io.github.adrian0511.prompt_link.dto.Message;
import io.github.adrian0511.prompt_link.dto.OpenRouterRequest;
import io.github.adrian0511.prompt_link.dto.OpenRouterResponse;
import io.github.adrian0511.prompt_link.exceptions.AiClientException;

/**
 * Punto de entrada de la librería: envía prompts al modelo configurado en {@link AiProperties} y
 * devuelve su respuesta.
 *
 * <p>La auto-configuración ya lo registra como bean, así que basta con inyectarlo:
 *
 * <pre>{@code
 * class ChatController {
 *
 *     private final AiService aiService;
 *
 *     ChatController(AiService aiService) {
 *         this.aiService = aiService;
 *     }
 *
 *     String ask(String prompt) {
 *         return aiService.generate(prompt).getContent();
 *     }
 * }
 * }</pre>
 *
 * <p>Cualquier fallo llega como {@link AiClientException}, cuyo {@code statusCode} distingue si fue
 * un error de la API, de red, de respuesta o de configuración.
 */
public class AiService {

    private final AiClient aiClient;
    private final AiProperties properties;

    public AiService(AiClient aiClient, AiProperties properties) {
        this.aiClient = aiClient;
        this.properties = properties;
    }

    /**
     * Envía un único mensaje de usuario al modelo.
     *
     * @param prompt la pregunta o instrucción para el modelo
     * @return la respuesta del modelo
     * @throws AiClientException si la llamada falla o la respuesta no es utilizable
     */
    public AiResponse generate(String prompt) {
        return generate(List.of(Message.user(prompt)));
    }

    /**
     * Envía un mensaje de usuario precedido de un prompt de sistema, con el que fijas el rol o el
     * tono del modelo.
     *
     * @param systemPrompt las instrucciones de comportamiento para el modelo
     * @param userPrompt la pregunta o instrucción del usuario
     * @return la respuesta del modelo
     * @throws AiClientException si la llamada falla o la respuesta no es utilizable
     */
    public AiResponse generate(String systemPrompt, String userPrompt) {
        return generate(List.of(Message.system(systemPrompt), Message.user(userPrompt)));
    }

    /**
     * Envía una conversación completa al modelo. Es la forma de mantener el hilo entre turnos: los
     * modelos no guardan memoria de las llamadas anteriores, así que el historial hay que
     * reenviarlo entero cada vez.
     *
     * <pre>{@code
     * aiService.generate(List.of(
     *         Message.system("Eres un asistente de soporte técnico."),
     *         Message.user("Mi aplicación no arranca."),
     *         Message.assistant("¿Qué error muestra el log?"),
     *         Message.user("NoSuchBeanDefinitionException")));
     * }</pre>
     *
     * @param messages la conversación en orden cronológico; no puede estar vacía
     * @return la respuesta del modelo
     * @throws IllegalArgumentException si la conversación es nula o está vacía
     * @throws AiClientException si la llamada falla o la respuesta no es utilizable
     */
    public AiResponse generate(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("La conversación debe tener al menos un mensaje");
        }
        requireApiKey();

        OpenRouterRequest request = new OpenRouterRequest();
        request.setModel(properties.getModel());
        request.setMessages(List.copyOf(messages));
        request.setMaxTokens(properties.getMaxTokens());
        request.setTemperature(properties.getTemperature());

        try {
            return new AiResponse(extractContent(aiClient.chatCompletion(request)));
        } catch (AiClientException e) {
            // Ya viene tipada desde AiErrorDecoder (o desde extractContent) con su status y su
            // cuerpo reales. Re-envolverla aquí los perdería, y todos los errores de la API
            // acabarían pareciendo errores de red.
            throw e;
        } catch (FeignException e) {
            // Un error de la API que AiErrorDecoder marcó como reintentable (429, 5xx) llega aquí
            // envuelto en una RetryableException, ya sea porque los reintentos están desactivados o
            // porque se agotaron. Dentro sigue estando la excepción tipada, con su status y su
            // cuerpo: hay que devolver esa, no la envoltura de Feign, que perdería el cuerpo.
            if (e.getCause() instanceof AiClientException error) {
                throw error;
            }
            // La petición no llegó a completarse: timeout, DNS, conexión rechazada. Feign lo
            // señaliza con una RetryableException, que es una FeignException con status() == -1.
            throw new AiClientException(
                    "Error de comunicación con la API de IA: " + e.getMessage(),
                    e.status(),
                    e.contentUTF8(),
                    e);
        } catch (Exception e) {
            throw new AiClientException(
                    "Error inesperado llamando a la API de IA: " + e.getMessage(),
                    AiClientException.NETWORK_ERROR,
                    null,
                    e);
        }
    }

    /**
     * Sin esta comprobación la cabecera saldría como {@code Bearer null} y OpenRouter respondería
     * un 401 que no dice nada de la causa real.
     */
    private void requireApiKey() {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new AiClientException(
                    "La propiedad ai.api-key no está configurada",
                    AiClientException.CONFIGURATION_ERROR,
                    null);
        }
    }

    /**
     * Un 200 no garantiza una respuesta utilizable: OpenRouter puede devolver la lista de choices
     * vacía, o un choice sin contenido, por ejemplo cuando el modelo filtra la petición.
     */
    private String extractContent(OpenRouterResponse response) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            throw new AiClientException(
                    "La API de IA devolvió una respuesta sin choices",
                    AiClientException.INVALID_RESPONSE,
                    null);
        }

        Message message = response.getChoices().get(0).getMessage();
        if (message == null || message.getContent() == null) {
            throw new AiClientException(
                    "La API de IA devolvió un choice sin contenido",
                    AiClientException.INVALID_RESPONSE,
                    null);
        }

        return message.getContent();
    }

}
