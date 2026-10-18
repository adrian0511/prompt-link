# Prompt Link

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java Version](https://img.shields.io/badge/Java-21+-blue.svg)](https://adoptium.net/)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.adrian0511/prompt-link.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.adrian0511/prompt-link)

**Prompt Link** is a reusable Java client for generative AI through OpenRouter, with Spring Boot auto-configuration. It lets you integrate models like GPT, Claude or Llama into your applications with typed error handling and, on WebFlux, token streaming.

## ✨ Features

- **OpenRouter integration** – Every model it offers.
- **Spring Boot auto-configuration** – Just add the dependency; **no `@EnableFeignClients` needed**.
- **Isolated Feign client** – Its interceptor and error decoder live only in the OpenRouter client's context, so **your API key never leaks into other Feign clients** of your application.
- **Typed error handling** – `AiClientException` tells API errors, network errors, unusable responses and configuration mistakes apart.
- **Token streaming** – `Flux<String>` on WebFlux: text arrives as it is generated.
- **Multi-turn conversations** – System prompt and message history.
- **Configurable timeouts and retries** – With defaults chosen for LLM workloads.
- **Fallback models** – If your model is down or rate limited, OpenRouter answers with the next one in the same request.
- **Reasoning control** – Turn reasoning off or cap it, so a thinking model does not spend the whole token budget before writing a word.

## ⚠️ Upgrading from 1.0.2

1.0.2 has two serious bugs, both fixed in 1.0.3:

- The interceptor adding `Authorization: Bearer <your-api-key>` was registered in the main context, so Feign applied it to **every** Feign client of your application: the OpenRouter API key travelled to any other service you called over Feign. **If you used 1.0.2 alongside other Feign clients, rotate your key.**
- Every HTTP error (401, 429, 402…) arrived with `statusCode = -1` and `errorBody = null`, indistinguishable from a network error.

## 📋 Requirements

- Java 21 or later
- Spring Boot 4.0.x (compatible with Spring Cloud 2025.1.x)
- Maven 3.6+

## 🚀 Installation

### Maven

```xml
<dependency>
    <groupId>io.github.adrian0511</groupId>
    <artifactId>prompt-link</artifactId>
    <version>1.2.0</version>
</dependency>
```

### Gradle

```groovy
implementation 'io.github.adrian0511:prompt-link:1.2.0'
```

## ⚙️ Configuration

In your `application.yml` (or `application.properties`):

```yaml
ai:
  api-key: ${OPENROUTER_API_KEY}   # Required. Never hardcode it.
  model: openai/gpt-4o-mini         # Model to use
  models:                           # Optional fallbacks, tried in order (at most 3)
    - anthropic/claude-3.5-haiku
  max-tokens: 5000                  # Maximum tokens in the answer
  reasoning:                        # Optional; omitted entirely unless you set something
    enabled: false                  # Turn reasoning off, on the models that allow it
    # effort: low                   # ...or limit it by effort (low, medium, high)
    # max-tokens: 200               # ...or by a token budget (not both)
    # exclude: true                 # Reason, but do not return the reasoning
  temperature: 0.7                  # Optional; omitted, the model's own default applies
  url: https://openrouter.ai/api/v1 # OpenRouter base URL (do not include /chat/completions)
  host: http://localhost:8080       # Your app's URL (HTTP-Referer header, for attribution)
  title: Spring AI Client           # Your app's name (X-Title header)
  connect-timeout: 10s              # Connection timeout
  read-timeout: 60s                 # Inactivity timeout (see below)
  retry:
    enabled: false                  # Retries on 429 and 5xx (see below)
    max-attempts: 2                 # Total attempts, including the first one
    period: 500ms                   # Initial wait; grows exponentially
    max-period: 5s                  # Cap on the wait between attempts
```

Only `api-key` is required; everything else defaults to the values above, except `models` and `reasoning`, which are not sent at all unless you configure them. Keep your key in an environment variable or in your platform's secret store.

### 🪂 Fallback models

A single model is a single point of failure, and free models in particular go down or get rate limited upstream for hours at a time. With `models`, OpenRouter tries each fallback in order whenever `model` fails, **within the same request**:

```yaml
ai:
  model: google/gemma-4-31b-it:free
  models:
    - nvidia/nemotron-3-ultra-550b-a55b:free
    - openrouter/free
```

- No extra round trip and no retry on your side, so it is **safe for streaming**: the first token comes from whichever model answered.
- At most **3** fallbacks, which is OpenRouter's limit. More than that fails with `CONFIGURATION_ERROR` instead of OpenRouter's opaque 400.
- Entries are trimmed and blank ones dropped, so `AI_MODELS=a, b,` from an environment variable works as expected.
- Unlike `retry`, this has no double-billing risk: only the model that actually answers is charged.

### 🧠 Reasoning models

Reasoning tokens come out of the **same `max-tokens` budget** as the answer. With a tight budget, a reasoning model can spend all of it thinking and return no text at all. `reasoning` lets you control that:

| Property | Sent as | Effect |
| --- | --- | --- |
| `enabled: false` | `"enabled": false` | Turns reasoning off, on the models that allow it. |
| `effort: low` | `"effort": "low"` | Limits how much the model thinks. Passed through as is. |
| `max-tokens: 200` | `"max_tokens": 200` | A token budget for the reasoning alone. |
| `exclude: true` | `"exclude": true` | The model reasons, but the reasoning is not returned. |

`effort` and `max-tokens` are mutually exclusive (OpenRouter rejects both together), and that combination fails with `CONFIGURATION_ERROR`. Models that do not reason ignore the block.

If a model still comes back empty, you get an `INVALID_RESPONSE` saying so, never an empty answer passed off as a success.

### 🔁 Retries

They are **disabled on purpose**. Generating an answer is not an idempotent operation: if the request is lost *after* the model has already processed it (a read timeout, say), retrying generates it again and **you get billed twice**.

Enable them with `ai.retry.enabled: true` if you would rather take that risk in exchange for surviving rate limits, which are frequent on OpenRouter. Once enabled:

- **429** responses are retried (the request was never processed, so repeating it is safe) along with **5xx**, using exponential backoff and honouring the `Retry-After` header when OpenRouter sends it.
- 401, 402, 404 and other request errors are **not** retried: repeating them would produce the exact same error.
- If the attempts run out, you get the same `AiClientException` you would have got without retries, with its `statusCode` and `errorBody` intact.
- In `stream(...)`, retries only happen **before the first token**. Once text has started arriving, retrying would resend the answer from the beginning and the user would see the sentence twice.

Timeouts and retries apply **equally to both paths**, blocking and reactive. `read-timeout` is an **inactivity** timeout, not a total one: it measures time spent receiving nothing over the network. For streaming that means an answer taking minutes to generate is not cut off while text keeps flowing; what gets detected is a stream that goes silent.

#### Mind the worst-case latency

Each attempt can exhaust the `read-timeout`, so with retries on, the longest you can wait before getting an error is roughly:

```
max-attempts × read-timeout + backoff
```

With the defaults (`2 × 60s`) that is about two minutes of the user staring at the screen. If yours is an interactive chat, tune `max-attempts` and `read-timeout` to whatever your UI can tolerate.

Be careful about lowering `read-timeout` too far: on a **non-streaming** call, OpenRouter sends no bytes at all until the model has finished generating, so that entire wait counts as inactivity. A 25s `read-timeout` would kill any answer that takes longer than 25 seconds to generate, and plenty do. With **streaming** the problem disappears, because the first token arrives quickly and the flow does not stop after that: there you can lower it safely. One more reason to prefer `stream(...)` in chat interfaces.

## 📝 Basic usage

No `@EnableFeignClients` needed. Just inject `AiService`:

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

### ⚡ Token streaming (WebFlux)

If your application is reactive, you can receive the text **as the model generates it** instead of waiting for the whole answer. That is what makes a chat feel alive.

Add WebFlux to *your* application (in this library it is an optional dependency, so if you skip this you never drag in reactor-netty):

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

With that, `ReactiveAiService` auto-configures itself:

```java
@RestController
public class ChatController {

    private final ReactiveAiService aiService;

    public ChatController(ReactiveAiService aiService) {
        this.aiService = aiService;
    }

    // Tokens reach the browser as they are generated
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam String prompt) {
        return aiService.stream(prompt);
    }

    // Or the whole answer, without blocking a thread
    @GetMapping("/ask")
    public Mono<String> ask(@RequestParam String prompt) {
        return aiService.generate(prompt).map(AiResponse::getContent);
    }
}
```

`stream(...)` takes the same variants as `generate(...)`: a bare prompt, system + user, or a `List<Message>` with the history. Errors are **identical** to the blocking service (`AiClientException` with the same `statusCode`), so there is no second error model to learn.

Fragments have no guaranteed size — one may be a word, a syllable or a punctuation mark. Concatenate them to rebuild the answer.

### System prompt and multi-turn conversations

```java
// With a system prompt
AiResponse response = aiService.generate("You are a concise Java expert.", "What is a record?");

// A full conversation, keeping the history across turns
List<Message> conversation = List.of(
        Message.system("You are a technical support assistant."),
        Message.user("My application will not start."),
        Message.assistant("What error does the log show?"),
        Message.user("NoSuchBeanDefinitionException"));

AiResponse response = aiService.generate(conversation);
```

## 🧩 Error handling

`AiService` throws `AiClientException` on any failure. The `statusCode` tells you what happened:

| `statusCode` | Constant | Meaning |
| --- | --- | --- |
| `> 0` | – | HTTP error from the API (401, 402, 429, 5xx…). `getErrorBody()` holds the body. |
| `-1` | `NETWORK_ERROR` | The call never completed: timeout, DNS failure, connection refused. |
| `-2` | `INVALID_RESPONSE` | The API answered 200 but with no `choices` or no text, including a stream that ends without a single token. When the cause is `finish_reason=length`, the message says so. |
| `-3` | `CONFIGURATION_ERROR` | `ai.api-key` is missing, `ai.models` has more than 3 entries, or `ai.reasoning.effort` and `ai.reasoning.max-tokens` are both set. |
| `-4` | `STREAM_ERROR` | The API failed mid-stream without a numeric HTTP code. Treated as transient. |

Use `isHttpError()` as a shortcut for the first case, and catch it globally with `@RestControllerAdvice`:

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

## 🛠️ Advanced customization

Every bean in this library uses `@ConditionalOnMissingBean`, so you can replace any of them with your own.

To change **Feign's** beans (interceptor, error decoder, timeouts, retryer), declare them in a client configuration class, not in a regular `@Configuration` — if you put them in the main context, Feign will apply them to every client of your application:

```java
// No @Configuration: Spring Cloud loads it only in this client's context
public class MyFeignConfig {

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
@FeignClient(name = "openrouter", url = "${ai.url}", configuration = MyFeignConfig.class)
public interface MyAiClient extends AiClient { }
```

To replace the service altogether, just declare your own `AiService` bean.

## 🧪 Building from source

```bash
git clone https://github.com/adrian0511/prompt-link.git
cd prompt-link
./mvnw clean verify
```

To publish to Maven Central (requires the GPG key):

```bash
./mvnw deploy -Prelease
```

## 📄 License

MIT. See the [LICENSE](LICENSE) file for details.

## 🤝 Contributing

Contributions are welcome. Please open an issue or a pull request on GitHub.

---

Thanks for using Prompt Link! 🚀
