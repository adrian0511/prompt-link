# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [1.1.0] - 2026-07-13

### Added

- **Token streaming.** `ReactiveAiService.stream(...)` returns a `Flux<String>` that emits text as the
  model generates it, instead of waiting for the whole answer. It auto-configures only when WebFlux is
  on the classpath: WebFlux is an **optional** dependency, so MVC applications never drag in
  reactor-netty.
- `ReactiveAiService.generate(...)` returns a `Mono<AiResponse>` for reactive applications that do not
  need streaming.
- Errors on the reactive path are the same as on the blocking one: `AiClientException`, with the same
  meaning in its `statusCode`. Moving from one to the other does not force you to rewrite error
  handling.
- Timeouts (`ai.connect-timeout`, `ai.read-timeout`) and retries (`ai.retry.*`) now apply to the
  reactive path as well. `read-timeout` is an **inactivity** timeout, not a total one: a long answer is
  not cut off while text keeps arriving.
- In `stream(...)` retries only happen before the first token: once text has been emitted, retrying
  would resend the answer from the beginning and the user would see it duplicated.
- New constant `AiClientException.STREAM_ERROR` for mid-stream failures with no numeric HTTP code.
  They are transient, so they are retried when they arrive before the first token. When OpenRouter does
  give a numeric code, its meaning is honoured: a 402 is not retried.
- The whole codebase, its Javadoc and its documentation are now in English.

### Changed

- `ai.retry.max-attempts` now defaults to **2** (was 3). Each attempt can exhaust the `read-timeout`, so
  every extra attempt multiplies how long the user waits before seeing the error.

### Fixed

- **Errors OpenRouter sends mid-stream are no longer swallowed in silence.** The API can fail after
  answering 200 and emitting tokens, sending the failure in an `error` field of an SSE event that also
  carries a `choices` entry with empty content. That event used to parse without complaint, the empty
  fragment was filtered out and the stream ended normally: the user saw their answer cut off
  mid-sentence and the application never knew. It now surfaces as an `AiClientException`.
- Response DTOs now ignore unknown properties explicitly. Deserialization used to work only because
  Spring Boot disables `FAIL_ON_UNKNOWN_PROPERTIES` by default: an application re-enabling it, or a new
  field in OpenRouter's API, would break it.

## [1.0.3] - 2026-07-13

### Security

- **The OpenRouter API key leaked to other services.** The `RequestInterceptor` adding the
  `Authorization` header was registered in the application's main context. Feign resolves interceptors
  by also looking at ancestor contexts, so it applied to **every** Feign client of the application
  using this library: any other service that application called over Feign received
  `Authorization: Bearer <your-api-key>`.

  The interceptor and the error decoder now live in `AiFeignConfiguration`, which Spring Cloud loads
  only in the OpenRouter client's context.

  **If you are on 1.0.2 and your application has other Feign clients, upgrade and rotate your API key.**

### Fixed

- Every HTTP error arrived with `statusCode = -1` and `errorBody = null`, indistinguishable from a
  network error. `AiClientException` now preserves the real status and body of the response.
- A 200 response with no `choices` caused an `IndexOutOfBoundsException` masked as an unexpected error.
  It now surfaces as `INVALID_RESPONSE`.
- With `ai.api-key` missing, the header was sent as `Bearer null` and OpenRouter answered an
  unexplained 401. It now fails with `CONFIGURATION_ERROR` and a clear message.
- `AiClientException` preserves the root cause, which used to be lost entirely.
- `mvn clean install` failed outside Windows: the GPG plugin had the executable path hardcoded and ran
  in the `verify` phase. Signing now lives in the `release` profile.
- The library shipped an `application.properties` inside its jar, which could interfere with the one in
  the consuming application.

### Added

- Multi-turn conversations and system prompt: `generate(List<Message>)` and
  `generate(String systemPrompt, String userPrompt)`.
- Optional retries with exponential backoff on rate limits (429) and server errors (5xx), honouring the
  `Retry-After` header. They are **disabled** by default (`ai.retry.enabled`): generating an answer is
  not idempotent, and a retry after a timeout can end up billed twice.
- Configurable timeouts (`ai.connect-timeout`, `ai.read-timeout`) with LLM-friendly defaults.
- `ai.temperature` and `ai.title` properties.
- `NETWORK_ERROR`, `INVALID_RESPONSE` and `CONFIGURATION_ERROR` constants, plus `isHttpError()`, to tell
  failure kinds apart without comparing magic numbers.
- A test suite (including one that checks, against a real HTTP server, that the API key does not leak to
  other Feign clients) and continuous integration.

### Changed

- Requirements corrected in the README: the project needs Java 21 and Spring Boot 4.0.x, not Java 17 and
  Boot 3.2.x as documented.

## [1.0.2] - 2026

- Unified error handling in `AiClientException`.

[1.1.0]: https://github.com/adrian0511/prompt-link/releases/tag/v1.1.0
[1.0.3]: https://github.com/adrian0511/prompt-link/releases/tag/v1.0.3
[1.0.2]: https://github.com/adrian0511/prompt-link/releases/tag/v1.0.2
