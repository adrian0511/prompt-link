package io.github.adrian0511.prompt_link.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.adrian0511.prompt_link.service.ReactiveAiService;
import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;

/**
 * Registra {@link ReactiveAiService} en aplicaciones que tengan WebFlux en el classpath.
 *
 * <p>WebFlux es una dependencia opcional de la librería: quien la use en una aplicación MVC no
 * arrastra reactor-netty y esta auto-configuración sencillamente no se activa. Para habilitar el
 * streaming basta con que el consumidor añada {@code spring-boot-starter-webflux}.
 *
 * <p>El {@link WebClient} se construye <strong>solo para esta librería</strong> y no se expone como
 * bean. Es deliberado, y por el mismo motivo por el que la configuración de Feign vive aparte: si
 * la cabecera {@code Authorization} se añadiera al {@code WebClient.Builder} de la aplicación, o si
 * se publicase aquí un builder global, la API key de OpenRouter viajaría a cualquier otro servicio
 * que la aplicación llamase con WebClient.
 */
@AutoConfiguration
@ConditionalOnClass(WebClient.class)
@EnableConfigurationProperties(AiProperties.class)
public class ReactiveAiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ReactiveAiService reactiveAiService(
            AiProperties properties,
            ObjectProvider<WebClient.Builder> builders,
            ObjectProvider<ObjectMapper> objectMappers) {

        // Se parte del builder de la aplicación si existe (para heredar sus codecs y su
        // configuración de conexión), pero clonándolo: el bean es prototype, así que la cabecera
        // Authorization que añadimos a continuación se queda en NUESTRA copia y no toca a los demás
        // WebClient de la aplicación.
        WebClient.Builder builder = builders.getIfAvailable(WebClient::builder).clone();

        WebClient webClient = builder
                .baseUrl(properties.getUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient(properties)))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .defaultHeader("HTTP-Referer", properties.getHost())
                .defaultHeader("X-Title", properties.getTitle())
                .build();

        return new ReactiveAiService(
                webClient, properties, objectMappers.getIfAvailable(ObjectMapper::new));
    }

    /**
     * Aplica {@code ai.connect-timeout} y {@code ai.read-timeout} al cliente reactivo.
     *
     * <p>Reactor Netty no trae ningún timeout de respuesta por defecto: sin esto, un servidor que
     * acepta la conexión y luego se queda mudo dejaría el {@code Mono} esperando para siempre. Y era
     * la peor variante del fallo, porque las dos propiedades existen y están documentadas, así que
     * quien las configuraba creía que hacían algo.
     *
     * <p>{@code responseTimeout} mide el tiempo <strong>entre lecturas de red</strong>, no el total:
     * una respuesta larga y legítima que tarde minutos en emitirse no se corta, mientras siga
     * llegando algo. Es la semántica que necesita el streaming, y además cuenta los keep-alives
     * ({@code : OPENROUTER PROCESSING}) que OpenRouter envía durante las pausas largas, que a este
     * nivel sí se ven aunque el decodificador de SSE los descarte después.
     */
    private static HttpClient httpClient(AiProperties properties) {
        return HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        (int) properties.getConnectTimeout().toMillis())
                .responseTimeout(properties.getReadTimeout());
    }

}
