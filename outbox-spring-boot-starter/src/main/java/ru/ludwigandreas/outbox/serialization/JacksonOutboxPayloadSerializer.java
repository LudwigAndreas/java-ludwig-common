package ru.ludwigandreas.outbox.serialization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.ludwigandreas.outbox.exception.OutboxSerializationException;

public class JacksonOutboxPayloadSerializer implements OutboxPayloadSerializer {

    private final ObjectMapper objectMapper;

    public JacksonOutboxPayloadSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new OutboxSerializationException(
                    "Failed to serialize outbox event payload of type " + payload.getClass().getName(), e);
        }
    }
}
