# Changelog

Todos los cambios relevantes de este proyecto se documentan aquí.

El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y el proyecto usa
[Versionado Semántico](https://semver.org/lang/es/).

## [1.0.3] - 2026-07-13

### Seguridad

- **La API key de OpenRouter se filtraba a otros servicios.** El `RequestInterceptor` que añade la
  cabecera `Authorization` se registraba en el contexto principal de la aplicación. Feign resuelve
  los interceptores mirando también los contextos ancestros, así que se aplicaba a **todos** los
  clientes Feign de la aplicación que usara la librería: cualquier otro servicio al que esa
  aplicación llamase por Feign recibía la cabecera `Authorization: Bearer <tu-api-key>`.

  Ahora el interceptor y el error decoder viven en `AiFeignConfiguration`, que Spring Cloud carga
  únicamente en el contexto del cliente de OpenRouter.

  **Si usas la 1.0.2 y tu aplicación tiene otros clientes Feign, actualiza y rota tu API key.**

### Corregido

- Todos los errores HTTP llegaban con `statusCode = -1` y `errorBody = null`, indistinguibles de un
  error de red. Ahora `AiClientException` conserva el código y el cuerpo reales de la respuesta.
- Una respuesta 200 sin `choices` provocaba un `IndexOutOfBoundsException` enmascarado como error
  inesperado. Ahora se comunica como `INVALID_RESPONSE`.
- Si faltaba `ai.api-key`, la cabecera se enviaba como `Bearer null` y OpenRouter devolvía un 401 sin
  explicación. Ahora falla con `CONFIGURATION_ERROR` y un mensaje claro.
- `AiClientException` conserva la causa raíz, que antes se perdía por completo.
- `mvn clean install` fallaba fuera de Windows: el plugin GPG tenía la ruta del ejecutable
  hardcodeada y se ejecutaba en la fase `verify`. La firma vive ahora en el profile `release`.
- La librería incluía un `application.properties` en su jar, que podía interferir con el de la
  aplicación que la usara.

### Añadido

- Conversaciones multi-turno y system prompt: `generate(List<Message>)` y
  `generate(String systemPrompt, String userPrompt)`.
- Timeouts configurables (`ai.connect-timeout`, `ai.read-timeout`) con valores por defecto pensados
  para LLM (10s y 60s).
- Propiedades `ai.temperature` y `ai.title`.
- Constantes `NETWORK_ERROR`, `INVALID_RESPONSE` y `CONFIGURATION_ERROR`, y el método
  `isHttpError()`, para distinguir los tipos de fallo sin comparar números mágicos.
- Suite de tests (incluida una que comprueba, contra un servidor HTTP real, que la API key no se
  filtra a otros clientes Feign) e integración continua.

### Cambiado

- Requisitos actualizados en el README: el proyecto exige Java 21 y Spring Boot 4.0.x, no Java 17 y
  Boot 3.2.x como se documentaba.

## [1.0.2] - 2026

- Manejo de errores unificado en `AiClientException`.

[1.0.3]: https://github.com/adrian0511/prompt-link/releases/tag/v1.0.3
[1.0.2]: https://github.com/adrian0511/prompt-link/releases/tag/v1.0.2
