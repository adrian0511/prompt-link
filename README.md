# Prompt Link

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java Version](https://img.shields.io/badge/Java-21+-blue.svg)](https://adoptium.net/)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.adrian0511/prompt-link.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.adrian0511/prompt-link)

**Prompt Link** es un cliente Java reutilizable para consumir IA generativa a través de OpenRouter, con auto‑configuración para Spring Boot. Permite integrar modelos como GPT-4, GPT-3.5, Claude, etc., en tus aplicaciones de forma sencilla y con manejo de errores personalizado.

## ✨ Características

- **Integración con OpenRouter** – Soporta todos los modelos disponibles.
- **Auto‑configuración Spring Boot** – Solo añade la dependencia; **no necesitas `@EnableFeignClients`**.
- **Cliente Feign** – Código declarativo y fácil de probar.
- **Manejo de errores unificado** – `AiClientException` cubre tanto errores HTTP (4xx/5xx) como errores de red (timeouts, DNS, etc.) con `statusCode = -1` para los de red.
- **Flexibilidad** – Permite sobrescribir beans y propiedades según necesidades.

## 📋 Requisitos

- Java 17 o superior
- Spring Boot 3.2.x (compatible con Spring Cloud 2023.0.x)
- Maven 3.6+

## 🚀 Instalación

### Maven

```xml
<dependency>
    <groupId>io.github.adrian0511</groupId>
    <artifactId>prompt-link</artifactId>
    <version>1.0.2</version>
</dependency>
```

Gradle

```groovy
implementation 'io.github.adrian0511:prompt-link:1.0.2'
```

⚙️ Configuración

En tu archivo application.yml (o application.properties):

```yaml
ai:
  api-key: ${OPENROUTER_API_KEY}   # Nunca hardcodees la clave
  host: http://localhost:8080       # URL de tu aplicación (usada en header HTTP-Referer)
  model: openai/gpt-4o-mini         # Modelo a utilizar
  max-tokens: 5000                  # Máximo de tokens en la respuesta
  url: https://openrouter.ai/api/v1 # Base URL de OpenRouter (no incluyas /chat/completions)
```

Importante: Mantén tu API key en variables de entorno o en un secreto de tu plataforma.

📝 Uso básico

Ya no necesitas añadir @EnableFeignClients. La librería se encarga automáticamente de registrar los beans necesarios.

Inyecta AiService en tu controlador o servicio:

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
            if (e.getStatusCode() == -1) {
                return "Error de red: " + e.getMessage();
            }
            return "Error HTTP " + e.getStatusCode() + ": " + e.getErrorBody();
        }
    }
}
```

🧩 Manejo de errores

La librería lanza AiClientException cuando ocurre un error en la llamada a OpenRouter. Puedes capturarla globalmente con @ControllerAdvice:

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AiClientException.class)
    public ResponseEntity<ErrorResponse> handleAiClientException(AiClientException ex) {
        ErrorResponse error = new ErrorResponse(ex.getMessage(), ex.getStatusCode());
        return ResponseEntity.status(ex.getStatusCode() == -1 ? 503 : ex.getStatusCode())
                             .body(error);
    }
}
```

🛠️ Personalización avanzada

La librería expone beans que puedes sobrescribir usando @ConditionalOnMissingBean. Por ejemplo, para cambiar los headers enviados:

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

🧪 Construcción desde fuente

Clona el repositorio y ejecuta:

```bash
git clone https://github.com/adrian0511/prompt-link.git
cd prompt-link
mvn clean install
```

📄 Licencia

Este proyecto está bajo la licencia MIT. Consulta el archivo LICENSE para más detalles.

🤝 Contribuciones

Las contribuciones son bienvenidas. Por favor, abre un issue o un pull request en GitHub.

---

¡Gracias por usar Prompt Link! 🚀