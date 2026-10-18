package io.github.adrian0511.prompt_link.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The library's configuration, under the {@code ai} prefix of your {@code application.yml}.
 *
 * <p>Only {@code api-key} is required; everything else has a sensible default. Each field's comment
 * is published in the configuration metadata, so your IDE shows it while autocompleting the
 * properties.
 */
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    /** OpenRouter API key. Required. */
    private String apiKey;

    /** OpenRouter base URL, without /chat/completions. */
    private String url = "https://openrouter.ai/api/v1";

    /** Model to use, in provider/model form. */
    private String model = "openai/gpt-4o-mini";

    /**
     * Fallback models, tried in order when {@code model} fails: provider down, rate limited, or the
     * request rejected. At most 3, which is OpenRouter's limit.
     *
     * <p>OpenRouter does the switching on its side within the same request, so a fallback costs no
     * extra round trip and is safe for streaming: the first token comes from whichever model
     * answered.
     */
    private List<String> models = new ArrayList<>();

    /** Maximum number of tokens in the answer. */
    private int maxTokens = 5000;

    /** Randomness of the answer (0.0-2.0). When null, the model's own default is used. */
    private Double temperature;

    /** Your application's URL, sent in the HTTP-Referer header OpenRouter uses for attribution. */
    private String host = "http://localhost:8080";

    /** Your application's name, sent in the X-Title header. */
    private String title = "Spring AI Client";

    /** Maximum time allowed to establish the connection. */
    private Duration connectTimeout = Duration.ofSeconds(10);

    /**
     * Maximum time without receiving anything over the network. It is an <em>inactivity</em>
     * timeout, not a total one: a long answer is not cut off as long as data keeps arriving.
     *
     * <p>Careful before lowering it: on a non-streaming call OpenRouter sends no bytes at all until
     * the model has finished generating, so that whole wait counts as inactivity.
     */
    private Duration readTimeout = Duration.ofSeconds(60);

    /** Retries on rate limits and server errors. Disabled by default. */
    private final Retry retry = new Retry();

    /** Reasoning control for models that think before answering. Not sent unless configured. */
    private final Reasoning reasoning = new Reasoning();

    /**
     * How much a reasoning model may think before answering.
     *
     * <p>It matters because reasoning tokens come out of the same {@code max-tokens} budget as the
     * answer: with a tight budget, a model can spend all of it thinking and return no text at all.
     *
     * <p>Every field is null by default and the whole block is then omitted, so each model keeps its
     * own behaviour. Models that do not reason ignore it.
     */
    public static class Reasoning {

        /** Set to false to turn reasoning off, on the models that allow it. */
        private Boolean enabled;

        /**
         * Reasoning effort: low, medium, high... Passed through as is, so new levels OpenRouter adds
         * work without a new release. Cannot be combined with max-tokens.
         */
        private String effort;

        /** Token budget for the reasoning alone. Cannot be combined with effort. */
        private Integer maxTokens;

        /** When true the model still reasons, but the reasoning is not returned in the response. */
        private Boolean exclude;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public String getEffort() {
            return effort;
        }

        /** Blank counts as unset, so {@code effort: ${SOME_VAR:}} can be left empty. */
        public void setEffort(String effort) {
            this.effort = effort == null || effort.isBlank() ? null : effort.strip();
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }

        public Boolean getExclude() {
            return exclude;
        }

        public void setExclude(Boolean exclude) {
            this.exclude = exclude;
        }

        /** Whether any field was configured; if not, no reasoning block is sent. */
        public boolean isConfigured() {
            return enabled != null || effort != null || maxTokens != null || exclude != null;
        }
    }

    /**
     * Retry policy for 429 (rate limit) and 5xx responses.
     *
     * <p>Disabled on purpose: generating an answer is not an idempotent operation. If the request is
     * lost <em>after</em> the model has already processed it, retrying generates it again and you
     * get billed twice. Enable it if you would rather take that risk in exchange for surviving rate
     * limits, which are frequent on OpenRouter.
     */
    public static class Retry {

        /** When enabled, 429 and 5xx responses are retried. */
        private boolean enabled = false;

        /**
         * Total number of attempts, including the first one.
         *
         * <p>Only 2 by default: in the worst case each attempt exhausts the read timeout, so every
         * extra attempt multiplies how long the user stares at the screen before seeing the error.
         * A single retry already catches most transient failures.
         */
        private int maxAttempts = 2;

        /** Initial wait between attempts; grows exponentially up to max-period. */
        private Duration period = Duration.ofMillis(500);

        /** Cap on the wait between attempts. It also caps how much of Retry-After is honoured. */
        private Duration maxPeriod = Duration.ofSeconds(5);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getPeriod() {
            return period;
        }

        public void setPeriod(Duration period) {
            this.period = period;
        }

        public Duration getMaxPeriod() {
            return maxPeriod;
        }

        public void setMaxPeriod(Duration maxPeriod) {
            this.maxPeriod = maxPeriod;
        }
    }

    public Retry getRetry() {
        return retry;
    }

    public Reasoning getReasoning() {
        return reasoning;
    }

    public List<String> getModels() {
        return models;
    }

    public void setModels(List<String> models) {
        this.models = models;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

}
