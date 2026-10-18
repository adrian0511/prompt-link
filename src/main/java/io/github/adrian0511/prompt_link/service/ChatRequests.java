package io.github.adrian0511.prompt_link.service;

import java.util.List;

import org.springframework.util.StringUtils;

import io.github.adrian0511.prompt_link.config.AiProperties;
import io.github.adrian0511.prompt_link.dto.Message;
import io.github.adrian0511.prompt_link.dto.OpenRouterRequest;
import io.github.adrian0511.prompt_link.exceptions.AiClientException;

/**
 * Builds the request body and checks the configuration, the same way for both services.
 *
 * <p>Configuration mistakes are reported as {@link AiClientException#CONFIGURATION_ERROR} on each
 * call instead of failing at startup. That keeps the existing contract for a missing API key, which
 * applications rely on to run in a degraded mode, and it turns OpenRouter's opaque 400s into a
 * message that names the property to fix.
 */
final class ChatRequests {

    /** OpenRouter rejects a longer {@code models} array with a 400. */
    static final int MAX_FALLBACK_MODELS = 3;

    private static final String LENGTH = "length";

    private ChatRequests() {
    }

    static OpenRouterRequest build(AiProperties properties, List<Message> messages, boolean streaming) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("The conversation must have at least one message");
        }
        requireValidConfiguration(properties);

        OpenRouterRequest request = new OpenRouterRequest();
        request.setModel(properties.getModel());
        List<String> fallbacks = fallbackModels(properties);
        request.setModels(fallbacks.isEmpty() ? null : fallbacks);
        request.setMessages(List.copyOf(messages));
        request.setMaxTokens(properties.getMaxTokens());
        request.setTemperature(properties.getTemperature());
        request.setStream(streaming ? Boolean.TRUE : null);
        request.setReasoning(reasoning(properties.getReasoning()));
        return request;
    }

    /**
     * The error for a 200 that brought no text.
     *
     * <p>{@code length} gets its own message because it is the case that actually happens: a
     * reasoning model spends the whole {@code max-tokens} budget thinking and never starts the
     * answer. Without the hint it looks like a broken model.
     */
    static AiClientException emptyAnswer(String finishReason, int maxTokens) {
        String message;
        if (LENGTH.equals(finishReason)) {
            message = "The model used up max-tokens (" + maxTokens + ") without writing any text. If"
                    + " it is a reasoning model, raise ai.max-tokens or limit it with ai.reasoning.*";
        } else if (finishReason != null) {
            message = "The AI API returned no content (finish_reason=" + finishReason + ")";
        } else {
            message = "The AI API returned no content";
        }
        return new AiClientException(message, AiClientException.INVALID_RESPONSE, null);
    }

    /**
     * Trimmed and without blanks: these usually come from an environment variable, where a stray
     * space or an empty entry is easy to miss, and OpenRouter would reject the id as unknown.
     */
    private static List<String> fallbackModels(AiProperties properties) {
        List<String> models = properties.getModels();
        if (models == null) {
            return List.of();
        }
        return models.stream()
                .filter(StringUtils::hasText)
                .map(String::strip)
                .toList();
    }

    private static void requireValidConfiguration(AiProperties properties) {
        // Without this check the header would go out as "Bearer null" and OpenRouter would answer a
        // 401 that says nothing about the real cause.
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw configurationError("The ai.api-key property is not configured");
        }
        if (fallbackModels(properties).size() > MAX_FALLBACK_MODELS) {
            throw configurationError("ai.models accepts at most " + MAX_FALLBACK_MODELS
                    + " fallback models, which is OpenRouter's limit");
        }
        AiProperties.Reasoning reasoning = properties.getReasoning();
        if (reasoning.getEffort() != null && reasoning.getMaxTokens() != null) {
            throw configurationError(
                    "ai.reasoning.effort and ai.reasoning.max-tokens cannot be set together");
        }
    }

    private static OpenRouterRequest.Reasoning reasoning(AiProperties.Reasoning config) {
        if (!config.isConfigured()) {
            return null;
        }
        OpenRouterRequest.Reasoning reasoning = new OpenRouterRequest.Reasoning();
        reasoning.setEnabled(config.getEnabled());
        reasoning.setEffort(config.getEffort());
        reasoning.setMaxTokens(config.getMaxTokens());
        reasoning.setExclude(config.getExclude());
        return reasoning;
    }

    private static AiClientException configurationError(String message) {
        return new AiClientException(message, AiClientException.CONFIGURATION_ERROR, null);
    }

}
