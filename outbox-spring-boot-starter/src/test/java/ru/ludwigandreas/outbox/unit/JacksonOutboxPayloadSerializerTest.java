package ru.ludwigandreas.outbox.unit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.outbox.exception.OutboxSerializationException;
import ru.ludwigandreas.outbox.serialization.JacksonOutboxPayloadSerializer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JacksonOutboxPayloadSerializerTest {

    @Test
    void serializesToJson() {
        JacksonOutboxPayloadSerializer serializer = new JacksonOutboxPayloadSerializer(new ObjectMapper());

        String json = serializer.serialize(Map.of("orderId", "42"));

        assertThat(json).isEqualTo("{\"orderId\":\"42\"}");
    }

    @Test
    void wrapsSerializationFailureInOutboxSerializationException() throws JsonProcessingException {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(new TestJsonProcessingException());
        JacksonOutboxPayloadSerializer serializer = new JacksonOutboxPayloadSerializer(failingMapper);

        assertThatThrownBy(() -> serializer.serialize("payload"))
                .isInstanceOf(OutboxSerializationException.class)
                .hasCauseInstanceOf(TestJsonProcessingException.class);
    }

    private static class TestJsonProcessingException extends JsonProcessingException {
        TestJsonProcessingException() {
            super("boom");
        }
    }
}
