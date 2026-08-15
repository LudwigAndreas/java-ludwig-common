package ru.ludwigandreas.outbox.serialization;

/**
 * Serializes an {@link ru.ludwigandreas.outbox.api.OutboxEvent} payload to the JSON text stored in the
 * {@code outbox_message.payload} column. Provide a bean of this type to override the default
 * Jackson-based implementation (e.g. to use a different library or a non-JSON wire format).
 */
public interface OutboxPayloadSerializer {

    String serialize(Object payload);
}
