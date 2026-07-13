package io.github.adrian0511.prompt_link.exceptions;

/**
 * Único fallo que la librería propaga al llamante. El {@link #getStatusCode() statusCode} dice qué
 * ocurrió:
 *
 * <ul>
 *   <li><b>mayor que 0</b> – error HTTP de la API (401, 402, 429, 5xx…). {@link #getErrorBody()}
 *       trae el cuerpo de la respuesta, que suele explicar el motivo.
 *   <li>{@link #NETWORK_ERROR} – la llamada no llegó a completarse.
 *   <li>{@link #INVALID_RESPONSE} – la API respondió correctamente pero sin contenido usable.
 *   <li>{@link #CONFIGURATION_ERROR} – falta configuración obligatoria.
 * </ul>
 *
 * <p>La distinción importa a la hora de reaccionar: un error HTTP suele ser culpa de la petición o
 * de la cuenta (clave inválida, sin créditos, rate limit) y repetir la llamada no ayuda, mientras
 * que un {@link #NETWORK_ERROR} sí es candidato a reintento.
 */
public class AiClientException extends RuntimeException {

    /** La llamada no llegó a completarse: timeout, DNS, conexión rechazada. */
    public static final int NETWORK_ERROR = -1;

    /** La API respondió con éxito pero el cuerpo no es utilizable: sin choices o sin contenido. */
    public static final int INVALID_RESPONSE = -2;

    /** La librería no está bien configurada: falta {@code ai.api-key}. */
    public static final int CONFIGURATION_ERROR = -3;

    /**
     * La API falló <em>a mitad de un stream</em>, después de haber respondido 200, y sin dar un
     * código HTTP numérico (manda cosas como {@code "server_error"}).
     *
     * <p>Se trata como transitorio: si el fallo llega antes del primer token, la llamada se
     * reintenta. Cuando OpenRouter sí da un código numérico se usa ese, con su significado normal:
     * un 402 a mitad de stream sigue sin reintentarse.
     */
    public static final int STREAM_ERROR = -4;

    private final int statusCode;
    private final String errorBody;

    public AiClientException(String message, int statusCode, String errorBody) {
        this(message, statusCode, errorBody, null);
    }

    public AiClientException(String message, int statusCode, String errorBody, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.errorBody = errorBody;
    }

    /**
     * El código HTTP devuelto por la API, o una de las constantes negativas de esta clase si el
     * fallo ocurrió antes de tener una respuesta.
     */
    public int getStatusCode() {
        return this.statusCode;
    }

    /**
     * El cuerpo crudo de la respuesta de error de la API, o {@code null} si el fallo no llegó a
     * producir una.
     */
    public String getErrorBody() {
        return this.errorBody;
    }

    /** {@code true} si el fallo viene de una respuesta HTTP de error de la API (4xx/5xx). */
    public boolean isHttpError() {
        return this.statusCode > 0;
    }
}
