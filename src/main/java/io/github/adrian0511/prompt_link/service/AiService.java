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
 * The entry point of this library: sends prompts to the model configured in {@link AiProperties} and
 * returns its answer.
 *
 * <p>The auto-configuration already registers it as a bean, so you only need to inject it:
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
 * <p>Every failure arrives as an {@link AiClientException}, whose {@code statusCode} tells whether it
 * was an API error, a network error, an unusable response or a configuration mistake.
 */
public class AiService {

    private final AiClient aiClient;
    private final AiProperties properties;

    public AiService(AiClient aiClient, AiProperties properties) {
        this.aiClient = aiClient;
        this.properties = properties;
    }

    /**
     * Sends a single user message to the model.
     *
     * @param prompt the question or instruction for the model
     * @return the model's answer
     * @throws AiClientException if the call fails or the answer is unusable
     */
    public AiResponse generate(String prompt) {
        return generate(List.of(Message.user(prompt)));
    }

    /**
     * Sends a user message preceded by a system prompt, which is how you set the model's role or
     * tone.
     *
     * @param systemPrompt the behaviour instructions for the model
     * @param userPrompt the user's question or instruction
     * @return the model's answer
     * @throws AiClientException if the call fails or the answer is unusable
     */
    public AiResponse generate(String systemPrompt, String userPrompt) {
        return generate(List.of(Message.system(systemPrompt), Message.user(userPrompt)));
    }

    /**
     * Sends a whole conversation to the model. This is how you keep the thread across turns: models
     * hold no memory of previous calls, so the entire history has to be resent every time.
     *
     * <pre>{@code
     * aiService.generate(List.of(
     *         Message.system("You are a technical support assistant."),
     *         Message.user("My application will not start."),
     *         Message.assistant("What error does the log show?"),
     *         Message.user("NoSuchBeanDefinitionException")));
     * }</pre>
     *
     * @param messages the conversation in chronological order; must not be empty
     * @return the model's answer
     * @throws IllegalArgumentException if the conversation is null or empty
     * @throws AiClientException if the call fails or the answer is unusable
     */
    public AiResponse generate(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("The conversation must have at least one message");
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
            // Already typed by AiErrorDecoder (or by extractContent), carrying its real status and
            // body. Re-wrapping it here would lose both, and every API error would end up looking
            // like a network error.
            throw e;
        } catch (FeignException e) {
            // An API error that AiErrorDecoder marked as retryable (429, 5xx) arrives here wrapped in
            // a RetryableException, either because retries are off or because they ran out. The typed
            // exception is still inside, with its status and its body: that is the one to hand back,
            // not Feign's wrapper, which would lose the body.
            if (e.getCause() instanceof AiClientException error) {
                throw error;
            }
            // The request never completed: timeout, DNS failure, connection refused. Feign signals
            // that with a RetryableException, which is a FeignException whose status() is -1.
            throw new AiClientException(
                    "Error communicating with the AI API: " + e.getMessage(),
                    e.status(),
                    e.contentUTF8(),
                    e);
        } catch (Exception e) {
            throw new AiClientException(
                    "Unexpected error calling the AI API: " + e.getMessage(),
                    AiClientException.NETWORK_ERROR,
                    null,
                    e);
        }
    }

    /**
     * Without this check the header would go out as {@code Bearer null} and OpenRouter would answer
     * a 401 that says nothing about the real cause.
     */
    private void requireApiKey() {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new AiClientException(
                    "The ai.api-key property is not configured",
                    AiClientException.CONFIGURATION_ERROR,
                    null);
        }
    }

    /**
     * A 200 does not guarantee a usable answer: OpenRouter may return an empty list of choices, or a
     * choice with no content, for instance when the model filters the request.
     */
    private String extractContent(OpenRouterResponse response) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            throw new AiClientException(
                    "The AI API returned a response with no choices",
                    AiClientException.INVALID_RESPONSE,
                    null);
        }

        Message message = response.getChoices().get(0).getMessage();
        if (message == null || message.getContent() == null) {
            throw new AiClientException(
                    "The AI API returned a choice with no content",
                    AiClientException.INVALID_RESPONSE,
                    null);
        }

        return message.getContent();
    }

}
