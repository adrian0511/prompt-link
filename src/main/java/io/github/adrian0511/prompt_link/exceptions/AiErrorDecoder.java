package io.github.adrian0511.prompt_link.exceptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import feign.Response;
import feign.codec.ErrorDecoder;

public class AiErrorDecoder implements ErrorDecoder {

    @Override
    public Exception decode(String methodKey, Response response) {
        String errorBody = "";
        try {
            if (response.body() != null) {
                errorBody = new String(response.body().asInputStream().readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {

        }

        return new AiClientException(
                "Error llamando a la API de IA: " + response.status(), response.status(), errorBody);
    }

}
