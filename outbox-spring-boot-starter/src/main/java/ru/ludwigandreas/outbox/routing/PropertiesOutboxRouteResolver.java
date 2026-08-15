package ru.ludwigandreas.outbox.routing;

import ru.ludwigandreas.outbox.api.OutboxEvent;
import ru.ludwigandreas.outbox.config.OutboxProperties;
import ru.ludwigandreas.outbox.exception.OutboxRoutingException;

import java.util.Map;

/**
 * Resolution order: {@link OutboxEvent#getRoute()} (explicit override, looked up in
 * {@code ludwig.outbox.routes}), then the first {@code ludwig.outbox.routes} entry whose
 * {@code event-types} contains the event's type, then {@code ludwig.outbox.default-route}.
 */
public class PropertiesOutboxRouteResolver implements OutboxRouteResolver {

    private final OutboxProperties properties;

    public PropertiesOutboxRouteResolver(OutboxProperties properties) {
        this.properties = properties;
    }

    @Override
    public OutboxRoute resolve(OutboxEvent event) {
        Map<String, OutboxProperties.Route> routes = properties.getRoutes();

        if (event.getRoute() != null) {
            OutboxProperties.Route route = routes.get(event.getRoute());
            if (route == null) {
                throw new OutboxRoutingException(
                        "OutboxEvent for eventType '" + event.getEventType() + "' requested route '"
                                + event.getRoute() + "', but no such entry exists under ludwig.outbox.routes");
            }
            return toOutboxRoute(route);
        }

        for (OutboxProperties.Route route : routes.values()) {
            if (route.getEventTypes().contains(event.getEventType())) {
                return toOutboxRoute(route);
            }
        }

        if (properties.getDefaultRoute() != null) {
            return toOutboxRoute(properties.getDefaultRoute());
        }

        throw new OutboxRoutingException(
                "No route resolved for eventType '" + event.getEventType() + "': no matching entry under "
                        + "ludwig.outbox.routes and no ludwig.outbox.default-route configured");
    }

    private static OutboxRoute toOutboxRoute(OutboxProperties.Route route) {
        return new OutboxRoute(route.getTransport(), route.getDestination());
    }
}
