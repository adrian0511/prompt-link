package io.github.adrian0511.prompt_link.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración de la librería, bajo el prefijo {@code ai} de tu {@code application.yml}.
 *
 * <p>Solo {@code api-key} es obligatoria; el resto tiene valores por defecto razonables. Los
 * comentarios de cada campo se publican en los metadatos de configuración, así que tu IDE los
 * mostrará al autocompletar las propiedades.
 */
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    /** Clave de API de OpenRouter. Obligatoria. */
    private String apiKey;

    /** URL base de OpenRouter, sin /chat/completions. */
    private String url = "https://openrouter.ai/api/v1";

    /** Modelo a utilizar, en formato proveedor/modelo. */
    private String model = "openai/gpt-4o-mini";

    /** Máximo de tokens de la respuesta. */
    private int maxTokens = 5000;

    /** Aleatoriedad de la respuesta (0.0-2.0). Si es null, se usa el default del modelo. */
    private Double temperature;

    /** URL de tu aplicación, enviada en la cabecera HTTP-Referer que OpenRouter usa para atribución. */
    private String host = "http://localhost:8080";

    /** Nombre de tu aplicación, enviado en la cabecera X-Title. */
    private String title = "Spring AI Client";

    /** Tiempo máximo para establecer la conexión. */
    private Duration connectTimeout = Duration.ofSeconds(10);

    /** Tiempo máximo de espera de la respuesta. Los modelos grandes pueden tardar. */
    private Duration readTimeout = Duration.ofSeconds(60);

    /** Reintentos ante rate limits y errores del servidor. Desactivados por defecto. */
    private final Retry retry = new Retry();

    /**
     * Política de reintentos ante 429 (rate limit) y 5xx.
     *
     * <p>Viene desactivada a propósito: generar una respuesta no es una operación idempotente. Si
     * la petición se pierde <em>después</em> de que el modelo la haya procesado, reintentar vuelve
     * a generarla y te la cobran dos veces. Actívala si prefieres tolerar ese riesgo a cambio de
     * aguantar los rate limits, que en OpenRouter son frecuentes.
     */
    public static class Retry {

        /** Si está activada, se reintentan los 429 y los 5xx. */
        private boolean enabled = false;

        /**
         * Número total de intentos, incluido el primero.
         *
         * <p>Solo 2 por defecto: en el peor caso cada intento agota el read-timeout, así que subir
         * este número multiplica lo que el usuario espera mirando la pantalla antes de ver el error.
         * Un único reintento ya captura la mayoría de los fallos transitorios.
         */
        private int maxAttempts = 2;

        /** Espera inicial entre intentos; crece exponencialmente hasta max-period. */
        private Duration period = Duration.ofMillis(500);

        /** Tope de la espera entre intentos. También limita lo que se respeta de Retry-After. */
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
