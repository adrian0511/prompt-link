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
