# Prompt Link

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java Version](https://img.shields.io/badge/Java-21+-blue.svg)](https://adoptium.net/)

**Prompt Link** es un cliente Java reutilizable para consumir IA generativa a través de OpenRouter, con auto‑configuración para Spring Boot. Te permite integrar modelos como GPT-4, GPT-3.5, Claude, etc., en tus aplicaciones de forma sencilla y con manejo de errores personalizado.

## ✨ Características

- **Integración con OpenRouter** – Soporta todos los modelos disponibles en OpenRouter.
- **Auto‑configuración Spring Boot** – Solo añade la dependencia y configura las propiedades.
- **Cliente Feign** – Código declarativo y fácil de probar.
- **Manejo de errores personalizado** – Lanza excepción `AiClientException` con detalles del error.
- **Flexibilidad** – Permite sobrescribir beans y propiedades según las necesidades del proyecto.

## 📋 Requisitos

- Java 17 o superior
- Spring Boot 3.2.x (compatible con Spring Cloud 2023.0.x)
- Maven 3.6+

## 🚀 Instalación

### Maven

Agrega la dependencia a tu `pom.xml`:

```xml
<dependency>
    <groupId>io.github.adrian0511</groupId>
    <artifactId>prompt-link</artifactId>
    <version>1.0.0</version>
</dependency>
```


## ⚙️ Configuración

En tu archivo `application.yml` (o `application.properties`):

```yaml
ai:
  api-key: ${OPENROUTER_API_KEY}   # Nunca hardcodees la clave
  host: http://localhost:8080       # URL de tu aplicación (usada en header HTTP-Referer)
  model: openai/gpt-4o-mini         # Modelo a utilizar
  max-tokens: 5000                  # Máximo de tokens en la respuesta
  url: https://openrouter.ai/api/v1 # Base URL de OpenRouter (no incluyas /chat/completions)
```

> **Importante:** Mantén tu API key en variables de entorno o en un secreto de tu plataforma.

## 📝 Uso básico

Habilita Feign en tu aplicación principal:

```java
@SpringBootApplication
@EnableFeignClients
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

Inyecta `AiService` en tu controlador o servicio y utiliza el método `generate`:

```java
@RestController
public class ChatController {

    private final AiService aiService;

    public ChatController(AiService aiService) {
        this.aiService = aiService;
    }

    @GetMapping("/ask")
    public String ask(@RequestParam String prompt) {
        try {
            AiResponse response = aiService.generate(prompt);
            return response.getContent();
        } catch (AiClientException e) {
            // Maneja el error (log, retorno amigable, etc.)
            return "Error: " + e.getMessage();
        }
    }
}
```

## 🧩 Manejo de errores

La librería lanza `AiClientException` cuando ocurre un error en la llamada a OpenRouter (códigos 4xx/5xx, timeouts, etc.). Puedes capturarla globalmente con `@ControllerAdvice`:

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AiClientException.class)
    public ResponseEntity<ErrorResponse> handleAiClientException(AiClientException ex) {
        ErrorResponse error = new ErrorResponse(ex.getMessage(), ex.getStatusCode());
        return ResponseEntity.status(ex.getStatusCode()).body(error);
    }
}
```

## 🛠️ Personalización avanzada

La librería expone beans que puedes sobrescribir usando `@ConditionalOnMissingBean`. Por ejemplo, para cambiar los headers enviados:

```java
@Configuration
public class CustomFeignConfig {

    @Bean
    public RequestInterceptor customInterceptor(AiProperties properties) {
        return template -> {
            template.header("Authorization", "Bearer " + properties.getApiKey());
            template.header("X-Custom-Header", "my-value");
        };
    }
}
```

## 🧪 Construcción desde fuente

Clona el repositorio y ejecuta:

```bash
git clone https://github.com/adrian0511/prompt-link.git
cd prompt-link
mvn clean install
```

## 📄 Licencia

Este proyecto está bajo la licencia MIT. Consulta el archivo [LICENSE](LICENSE) para más detalles.

---

¡Gracias por usar Prompt Link! 