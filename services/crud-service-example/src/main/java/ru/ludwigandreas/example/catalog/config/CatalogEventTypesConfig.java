package ru.ludwigandreas.example.catalog.config;

import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.example.catalog.service.event.ProductEventPayload;
import ru.ludwigandreas.example.catalog.service.event.ProductEventType;
import ru.ludwigandreas.outbox.api.OutboxEventTypes;

/**
 * What each event this service publishes through the outbox carries.
 *
 * <p>The routes in {@code application.yml} say where each event goes; this says what it is, which is what
 * the AsyncAPI document describes. {@code NotifyProductCreated} is sent down a route named in code, so the
 * route is declared with it.
 */
@Configuration(proxyBeanMethods = false)
public class CatalogEventTypesConfig {

    /** The route ProductServiceImpl names for the steward notification. */
    static final String NOTIFICATION_ROUTE = "product-notifications";

    /**
     * The declarations.
     *
     * @return the event types
     */
    @Bean
    OutboxEventTypes catalogEventTypes() {
        return OutboxEventTypes.merge(List.of(
                OutboxEventTypes.of(Map.of(
                        ProductEventType.CREATED, ProductEventPayload.class,
                        ProductEventType.UPDATED, ProductEventPayload.class,
                        ProductEventType.DELETED, ProductEventPayload.class)),
                OutboxEventTypes.routed(ProductEventType.NOTIFY_CREATED, ProductEventPayload.class,
                        NOTIFICATION_ROUTE)));
    }
}
