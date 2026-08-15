package ru.ludwigandreas.outbox.unit;

import org.junit.jupiter.api.Test;
import ru.ludwigandreas.outbox.api.OutboxEvent;
import ru.ludwigandreas.outbox.config.OutboxProperties;
import ru.ludwigandreas.outbox.exception.OutboxRoutingException;
import ru.ludwigandreas.outbox.routing.OutboxRoute;
import ru.ludwigandreas.outbox.routing.PropertiesOutboxRouteResolver;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PropertiesOutboxRouteResolverTest {

    private static OutboxProperties.Route route(String transport, String destination, String... eventTypes) {
        OutboxProperties.Route route = new OutboxProperties.Route();
        route.setTransport(transport);
        route.setDestination(destination);
        route.setEventTypes(List.of(eventTypes));
        return route;
    }

    private static OutboxEvent event(String eventType) {
        return OutboxEvent.builder().aggregateType("Order").aggregateId("1").eventType(eventType).payload("p").build();
    }

    @Test
    void resolvesByEventTypeMatch() {
        OutboxProperties properties = new OutboxProperties();
        properties.setRoutes(Map.of("orders", route("KAFKA", "orders-topic", "OrderCreated")));
        PropertiesOutboxRouteResolver resolver = new PropertiesOutboxRouteResolver(properties);

        OutboxRoute resolved = resolver.resolve(event("OrderCreated"));

        assertThat(resolved).isEqualTo(new OutboxRoute("KAFKA", "orders-topic"));
    }

    @Test
    void explicitRouteOverrideTakesPrecedence() {
        OutboxProperties properties = new OutboxProperties();
        properties.setRoutes(Map.of(
                "orders", route("KAFKA", "orders-topic", "OrderCreated"),
                "audit", route("REST", "https://audit.example.com")));
        PropertiesOutboxRouteResolver resolver = new PropertiesOutboxRouteResolver(properties);

        OutboxEvent event = OutboxEvent.builder()
                .aggregateType("Order").aggregateId("1").eventType("OrderCreated").payload("p").route("audit").build();

        assertThat(resolver.resolve(event)).isEqualTo(new OutboxRoute("REST", "https://audit.example.com"));
    }

    @Test
    void fallsBackToDefaultRoute() {
        OutboxProperties properties = new OutboxProperties();
        properties.setDefaultRoute(route("REST", "https://fallback.example.com"));
        PropertiesOutboxRouteResolver resolver = new PropertiesOutboxRouteResolver(properties);

        assertThat(resolver.resolve(event("Unmatched"))).isEqualTo(new OutboxRoute("REST", "https://fallback.example.com"));
    }

    @Test
    void throwsWhenNothingMatchesAndNoDefault() {
        OutboxProperties properties = new OutboxProperties();
        PropertiesOutboxRouteResolver resolver = new PropertiesOutboxRouteResolver(properties);

        assertThatThrownBy(() -> resolver.resolve(event("Unmatched")))
                .isInstanceOf(OutboxRoutingException.class);
    }

    @Test
    void throwsWhenExplicitRouteNameDoesNotExist() {
        OutboxProperties properties = new OutboxProperties();
        PropertiesOutboxRouteResolver resolver = new PropertiesOutboxRouteResolver(properties);

        OutboxEvent event = OutboxEvent.builder()
                .aggregateType("Order").aggregateId("1").eventType("OrderCreated").payload("p").route("missing").build();

        assertThatThrownBy(() -> resolver.resolve(event)).isInstanceOf(OutboxRoutingException.class);
    }
}
