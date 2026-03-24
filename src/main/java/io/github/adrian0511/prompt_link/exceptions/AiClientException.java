package io.github.adrian0511.prompt_link.exceptions;

public class AiClientException extends RuntimeException {
    private final int statusCode;
    private final String errorBody;

    public AiClientException(String message, int statusCode, String errorBody) {
        super(message);
        this.statusCode = statusCode;
        this.errorBody = errorBody;
    }

    public int getStatusCode() {
        return this.statusCode;
    }

    public String getErrorBody() {
        return this.errorBody;
    }
}
