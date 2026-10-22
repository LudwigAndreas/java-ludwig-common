package ru.ludwigandreas.notification.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.notification.service.event.NotificationEventType;
import ru.ludwigandreas.notification.service.event.NotificationLifecyclePayload;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.outbox.api.OutboxEventTypes;

/**
 * What each lifecycle event this service publishes through the outbox carries.
 *
 * <p>{@code NotificationEventPublisher} sends them down the route named in
 * {@code ludwig.notification.events.route}, so each is declared with that route.
 */
@Configuration(proxyBeanMethods = false)
public class NotificationEventTypesConfig {

    /**
     * The declarations.
     *
     * @param properties names the route
     * @return the event types
     */
    @Bean
    OutboxEventTypes notificationEventTypes(NotificationProperties properties) {
        String route = properties.getEvents().getRoute();
        return OutboxEventTypes.merge(List.of(
                OutboxEventTypes.routed(NotificationEventType.DELIVERED, NotificationLifecyclePayload.class, route),
                OutboxEventTypes.routed(NotificationEventType.FAILED, NotificationLifecyclePayload.class, route),
                OutboxEventTypes.routed(NotificationEventType.SUPPRESSED, NotificationLifecyclePayload.class,
                        route)));
    }
}
