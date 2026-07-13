# Prompt Link

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java Version](https://img.shields.io/badge/Java-21+-blue.svg)](https://adoptium.net/)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.adrian0511/prompt-link.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.adrian0511/prompt-link)

**Prompt Link** es un cliente Java reutilizable para consumir IA generativa a través de OpenRouter, con auto‑configuración para Spring Boot. Permite integrar modelos como GPT, Claude, Llama, etc., en tus aplicaciones de forma sencilla y con manejo de errores tipado.

## ✨ Características

- **Integración con OpenRouter** – Soporta todos los modelos disponibles.
- **Auto‑configuración Spring Boot** – Solo añade la dependencia; **no necesitas `@EnableFeignClients`**.
- **Cliente Feign aislado** – Su interceptor y su error decoder viven solo en el contexto del cliente de OpenRouter, así que **tu API key nunca se filtra a otros clientes Feign** de tu aplicación.
- **Manejo de errores tipado** – `AiClientException` distingue errores HTTP, de red, de respuesta y de configuración.
- **Streaming de tokens** – `Flux<String>` sobre WebFlux: el texto llega según se genera.
- **Conversaciones multi‑turno** – System prompt e historial de mensajes.
- **Timeouts configurables** – Con valores por defecto pensados para LLM.

## ⚠️ Actualiza desde 1.0.2

La 1.0.2 tiene dos fallos serios, ambos corregidos en 1.0.3:

- El interceptor que añade `Authorization: Bearer <tu-api-key>` se registraba en el contexto principal, así que Feign lo aplicaba a **todos** los clientes Feign de tu aplicación: la API key de OpenRouter viajaba a cualquier otro servicio que llamases por Feign.
- Todos los errores HTTP (401, 429, 402…) llegaban con `statusCode = -1` y `errorBody = null`, indistinguibles de un error de red.

## 📋 Requisitos

- Java 21 o superior
- Spring Boot 4.0.x (compatible con Spring Cloud 2025.1.x)
- Maven 3.6+

## 🚀 Instalación

### Maven

```xml
<dependency>
    <groupId>io.github.adrian0511</groupId>
    <artifactId>prompt-link</artifactId>
    <version>1.1.0</version>
</dependency>
```

### Gradle

```groovy
implementation 'io.github.adrian0511:prompt-link:1.1.0'
```

## ⚙️ Configuración

En tu `application.yml` (o `application.properties`):

```yaml
ai:
  api-key: ${OPENROUTER_API_KEY}   # Obligatoria. Nunca la hardcodees.
  model: openai/gpt-4o-mini         # Modelo a utilizar
  max-tokens: 5000                  # Máximo de tokens en la respuesta
  temperature: 0.7                  # Opcional; si se omite, se usa el default del modelo
  url: https://openrouter.ai/api/v1 # Base URL de OpenRouter (no incluyas /chat/completions)
  host: http://localhost:8080       # URL de tu app (cabecera HTTP-Referer, para atribución)
  title: Spring AI Client           # Nombre de tu app (cabecera X-Title)
  connect-timeout: 10s              # Timeout de conexión
  read-timeout: 60s                 # Timeout de lectura; súbelo con modelos grandes
  retry:
    enabled: false                  # Reintentos ante 429 y 5xx (ver más abajo)
    max-attempts: 3                 # Intentos totales, incluido el primero
    period: 500ms                   # Espera inicial; crece exponencialmente
    max-period: 5s                  # Tope de la espera entre intentos
```

Solo `api-key` es obligatoria; el resto tiene los valores por defecto de arriba. Mantén tu clave en variables de entorno o en un secreto de tu plataforma.

### 🔁 Reintentos

Vienen **desactivados a propósito**. Generar una respuesta no es una operación idempotente: si la petición se pierde *después* de que el modelo la haya procesado (por ejemplo, un timeout de lectura), reintentarla la genera otra vez y **te la cobran dos veces**.

Actívalos con `ai.retry.enabled: true` si prefieres asumir ese riesgo a cambio de aguantar los rate limits, que en OpenRouter son frecuentes. Cuando están activos:

- Se reintentan los **429** (la petición ni llegó a procesarse, así que reintentarla es seguro) y los **5xx**, con backoff exponencial y respetando la cabecera `Retry-After` si OpenRouter la envía.
- **No** se reintentan los 401, 402, 404 ni el resto de errores de la petición: repetirlos daría exactamente el mismo error.
- Si se agotan los intentos, recibes la misma `AiClientException` que recibirías sin reintentos, con su `statusCode` y su `errorBody` intactos.
- En `stream(...)`, solo se reintenta **antes del primer token**. Una vez has empezado a recibir texto, reintentar reenviaría la respuesta desde el principio y el usuario vería la frase duplicada en pantalla.

Los timeouts y los reintentos se aplican **igual en los dos caminos**, el bloqueante y el reactivo. En streaming, el `read-timeout` es un timeout de **inactividad**, no total: una respuesta que tarde varios minutos en generarse no se corta mientras siga llegando texto; lo que se detecta es que el stream se quede mudo.

## 📝 Uso básico

No necesitas `@EnableFeignClients`. Inyecta `AiService` directamente:

```java
@RestController
public class ChatController {

    private final AiService aiService;

    public ChatController(AiService aiService) {
        this.aiService = aiService;
    }

    @GetMapping("/ask")
    public String ask(@RequestParam String prompt) {
        return aiService.generate(prompt).getContent();
    }
}
```

### ⚡ Streaming de tokens (WebFlux)

Si tu aplicación es reactiva, puedes recibir el texto **según el modelo lo genera**, en vez de esperar a la respuesta completa. Es lo que hace que un chat se sienta vivo.

Añade WebFlux a *tu* aplicación (en la librería es una dependencia opcional, así que si no lo haces no arrastras reactor-netty):

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

Con eso, `ReactiveAiService` se autoconfigura solo:

```java
@RestController
public class ChatController {

    private final ReactiveAiService aiService;

    public ChatController(ReactiveAiService aiService) {
        this.aiService = aiService;
    }

    // Los tokens llegan al navegador según se generan
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam String prompt) {
        return aiService.stream(prompt);
    }

    // Y la respuesta completa, sin bloquear ningún hilo
    @GetMapping("/ask")
    public Mono<String> ask(@RequestParam String prompt) {
        return aiService.generate(prompt).map(AiResponse::getContent);
    }
}
```

`stream(...)` acepta las mismas variantes que `generate(...)`: prompt suelto, system + user, o una `List<Message>` con el historial. Los errores son **idénticos** a los del servicio bloqueante (`AiClientException` con el mismo `statusCode`), así que no tienes que aprender dos modelos de errores.

Los fragmentos no tienen un tamaño garantizado — pueden ser una palabra, una sílaba o un signo de puntuación. Concaténalos para reconstruir la respuesta.

### System prompt y conversaciones multi‑turno

```java
// Con system prompt
AiResponse response = aiService.generate("Eres un experto en Java conciso.", "¿Qué es un record?");

// Conversación completa, manteniendo el historial entre turnos
List<Message> conversacion = List.of(
        Message.system("Eres un asistente de soporte técnico."),
        Message.user("Mi aplicación no arranca."),
        Message.assistant("¿Qué error muestra el log?"),
        Message.user("NoSuchBeanDefinitionException"));

AiResponse response = aiService.generate(conversacion);
```

## 🧩 Manejo de errores

`AiService` lanza `AiClientException` ante cualquier fallo. El `statusCode` te dice qué pasó:

| `statusCode` | Constante | Significado |
| --- | --- | --- |
| `> 0` | – | Error HTTP de la API (401, 402, 429, 5xx…). `getErrorBody()` trae el cuerpo. |
| `-1` | `NETWORK_ERROR` | La llamada no llegó a completarse: timeout, DNS, conexión rechazada. |
| `-2` | `INVALID_RESPONSE` | La API respondió 200 pero sin `choices` o sin contenido utilizable. |
| `-3` | `CONFIGURATION_ERROR` | Falta `ai.api-key`. |

Puedes usar `isHttpError()` como atajo para el primer caso, y capturarla globalmente con `@RestControllerAdvice`:

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AiClientException.class)
    public ResponseEntity<String> handleAiClientException(AiClientException ex) {
        HttpStatus status = ex.isHttpError()
                ? HttpStatus.valueOf(ex.getStatusCode())
                : HttpStatus.SERVICE_UNAVAILABLE;

        return ResponseEntity.status(status).body(ex.getMessage());
    }
}
```

## 🛠️ Personalización avanzada

Todos los beans de la librería usan `@ConditionalOnMissingBean`, así que puedes sustituirlos por los tuyos.

Para cambiar los beans **de Feign** (interceptor, error decoder, timeouts), decláralos en una clase de configuración del cliente, no en una `@Configuration` normal — si los pones en el contexto principal, Feign los aplicará a todos los clientes de tu aplicación:

```java
// Sin @Configuration: Spring Cloud la carga solo en el contexto de este cliente
public class MiFeignConfig {

    @Bean
    public RequestInterceptor customInterceptor(AiProperties properties) {
        return template -> {
            template.header("Authorization", "Bearer " + properties.getApiKey());
            template.header("X-Custom-Header", "my-value");
        };
    }
}
```

```java
@FeignClient(name = "openrouter", url = "${ai.url}", configuration = MiFeignConfig.class)
public interface MiAiClient extends AiClient { }
```

Para sustituir el servicio entero, basta con declarar tu propio bean `AiService`.

## 🧪 Construcción desde fuente

```bash
git clone https://github.com/adrian0511/prompt-link.git
cd prompt-link
./mvnw clean verify
```

Para publicar en Maven Central (requiere la clave GPG):

```bash
./mvnw deploy -Prelease -Dgpg.passphrase=...
```

## 📄 Licencia

Este proyecto está bajo la licencia MIT. Consulta el archivo [LICENSE](LICENSE) para más detalles.

## 🤝 Contribuciones

Las contribuciones son bienvenidas. Por favor, abre un issue o un pull request en GitHub.

---

¡Gracias por usar Prompt Link! 🚀
