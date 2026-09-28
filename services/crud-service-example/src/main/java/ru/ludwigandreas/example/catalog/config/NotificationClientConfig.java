package ru.ludwigandreas.example.catalog.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.example.catalog.client.NotificationApi;
import ru.ludwigandreas.example.catalog.service.notification.ProductNotificationDispatcher;

/**
 * Wires the outbound call to the notification service.
 *
 * <p>Note how little is here, and what is not. There is no {@code RestClient} builder, no
 * {@code ClientHttpRequestFactory}, no timeout, no interceptor adding a token, no {@code @Retryable}
 * and no circuit breaker - all of those are properties of the {@code notifications} client in
 * {@code application.yml}, and the {@link NotificationApi} proxy is registered by the rest-client
 * starter's own scan of this application's packages. This class exists only to connect two things
 * that neither starter can connect for us: our dispatcher and the outbox's transport registry.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CatalogNotificationProperties.class)
public class NotificationClientConfig {

    /**
     * Registers the dispatcher under its transport name.
     *
     * <p>{@code OutboxDispatcherRegistry} is built from every {@code OutboxDispatcher} bean in the
     * context, so publishing this one is the whole registration - nothing names it anywhere else, and
     * the route's {@code transport: NOTIFICATIONS} is matched against {@code transport()}.
     *
     * <p>The object mapper is the application's own, so a product payload is read back exactly as the
     * publisher wrote it. The outbound body is serialized by the {@code notifications} client's mapper
     * instead, which is configured separately and reads tolerantly - two mappers on purpose, because
     * what this service stores and what a peer accepts are different contracts.
     *
     * @param notifications the declarative client
     * @param properties    what to ask for
     * @param objectMapper  the application's mapper, for reading the stored payload
     * @return the dispatcher
     */
    @Bean
    public ProductNotificationDispatcher productNotificationDispatcher(
            NotificationApi notifications,
            CatalogNotificationProperties properties,
            ObjectMapper objectMapper) {
        return new ProductNotificationDispatcher(notifications, properties, objectMapper);
    }
}
