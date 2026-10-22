package ru.ludwigandreas.outbox.api;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Which payload class each outbox event type carries, and - for an event published with an explicit
 * {@link OutboxEvent#getRoute() route} - which route.
 *
 * <p>{@link OutboxEvent#getPayload()} is {@code Object}, so nothing downstream of the publisher can know
 * what an event looks like - not a generated AsyncAPI document, not a reviewer. A service declares it once
 * per event type, as a bean:
 *
 * <pre>{@code
 * @Bean
 * OutboxEventTypes catalogEvents() {
 *     return OutboxEventTypes.merge(List.of(
 *             OutboxEventTypes.of(Map.of("ProductCreated", ProductEventPayload.class)),
 *             OutboxEventTypes.routed("NotifyProductCreated", ProductEventPayload.class, "product-notifications")));
 * }
 * }</pre>
 *
 * <p>The topic and cluster are not repeated here: the routes already say them. An event resolved by its
 * type needs nothing more. An event the code sends down a route by name has to say which route, because
 * that choice is made at publish time and no configuration records it. Several beans are merged; one event
 * type declared twice differently fails startup.
 */
public final class OutboxEventTypes {

    private static final OutboxEventTypes NONE = new OutboxEventTypes(Map.of());

    private final Map<String, Declaration> declarations;

    private OutboxEventTypes(Map<String, Declaration> declarations) {
        this.declarations = Map.copyOf(declarations);
    }

    /**
     * Event types whose route is resolved from their type.
     *
     * @param types event type to payload class
     * @return the declaration
     */
    public static OutboxEventTypes of(Map<String, Class<?>> types) {
        Map<String, Declaration> declarations = new LinkedHashMap<>();
        types.forEach((type, payload) -> declarations.put(type, new Declaration(payload, null)));
        return new OutboxEventTypes(declarations);
    }

    /**
     * An event type the code publishes down a named route.
     *
     * @param eventType the event type
     * @param payload   its payload class
     * @param route     the {@code ludwig.outbox.routes} entry it is published with
     * @return the declaration
     */
    public static OutboxEventTypes routed(String eventType, Class<?> payload, String route) {
        return new OutboxEventTypes(Map.of(eventType, new Declaration(payload, Objects.requireNonNull(route))));
    }

    /**
     * No declarations.
     *
     * @return the empty declaration
     */
    public static OutboxEventTypes none() {
        return NONE;
    }

    /**
     * Several declarations as one.
     *
     * @param declarations every declaration in the context
     * @return the merged declaration
     * @throws IllegalStateException naming the event type when two declarations disagree about it
     */
    public static OutboxEventTypes merge(Collection<OutboxEventTypes> declarations) {
        Map<String, Declaration> merged = new LinkedHashMap<>();
        for (OutboxEventTypes declaration : declarations) {
            declaration.declarations.forEach((type, declared) -> {
                Declaration existing = merged.putIfAbsent(type, declared);
                if (existing != null && !existing.equals(declared)) {
                    throw new IllegalStateException("Outbox event type '" + type + "' is declared twice, differently: "
                            + existing + " and " + declared);
                }
            });
        }
        return new OutboxEventTypes(merged);
    }

    /**
     * The payload class of an event type.
     *
     * @param eventType the event type
     * @return the declared class, or empty when the type is not declared
     */
    public Optional<Class<?>> payloadOf(String eventType) {
        return Optional.ofNullable(declarations.get(eventType)).map(Declaration::payload);
    }

    /**
     * Every declaration.
     *
     * @return event type to declaration
     */
    public Map<String, Declaration> declarations() {
        return declarations;
    }

    /**
     * One event type's declaration.
     *
     * @param payload the payload class
     * @param route   the route it is published with by name, or {@code null} when it is resolved by type
     */
    public record Declaration(Class<?> payload, String route) {

        /** Validates the payload class. */
        public Declaration {
            Objects.requireNonNull(payload, "payload");
        }
    }
}
