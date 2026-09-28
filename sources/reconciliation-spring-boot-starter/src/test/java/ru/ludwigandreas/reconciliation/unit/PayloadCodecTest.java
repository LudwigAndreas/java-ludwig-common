package ru.ludwigandreas.reconciliation.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.reconciliation.exception.ReconciliationSerializationException;
import ru.ludwigandreas.reconciliation.payload.PayloadCodec;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class PayloadCodecTest {

    private final PayloadCodec codec = new PayloadCodec(new ObjectMapper());

    /**
     * The whole reason the hash is taken over a normalized form. A partner that re-serializes the same
     * record with its fields in a different order has told us nothing new, and reporting it as changed
     * costs a write, an updated_at bump, an audit row and a downstream event on every single poll.
     */
    @Test
    void objectKeyOrderDoesNotChangeTheHash() {
        String first = "{\"id\":\"A-1\",\"status\":\"PAID\",\"amount\":1200}";
        String reordered = "{\"amount\":1200,\"status\":\"PAID\",\"id\":\"A-1\"}";

        assertThat(codec.hashJson(first)).isEqualTo(codec.hashJson(reordered));
    }

    @Test
    void nestedObjectKeyOrderDoesNotChangeTheHash() {
        String first = "{\"id\":\"A-1\",\"customer\":{\"name\":\"n\",\"tier\":\"gold\"}}";
        String reordered = "{\"customer\":{\"tier\":\"gold\",\"name\":\"n\"},\"id\":\"A-1\"}";

        assertThat(codec.hashJson(first)).isEqualTo(codec.hashJson(reordered));
    }

    /**
     * Array order is significant and is deliberately not normalized: a partner that reorders a list has
     * said something different, even when the set of elements is the same.
     */
    @Test
    void arrayOrderDoesChangeTheHash() {
        String first = "{\"lines\":[\"a\",\"b\"]}";
        String reordered = "{\"lines\":[\"b\",\"a\"]}";

        assertThat(codec.hashJson(first)).isNotEqualTo(codec.hashJson(reordered));
    }

    @Test
    void aChangedValueChangesTheHash() {
        assertThat(codec.hashJson("{\"status\":\"PAID\"}"))
                .isNotEqualTo(codec.hashJson("{\"status\":\"REFUNDED\"}"));
    }

    @Test
    void hashingAnObjectAgreesWithHashingItsSerializedForm() {
        Map<String, Object> record = Map.of("id", "A-1", "amount", 1200);

        assertThat(codec.hash(record)).isEqualTo(codec.hashJson(codec.serialize(record)));
    }

    @Test
    void roundTripsAStagedPayload() {
        String payload = codec.serialize(Map.of("id", "A-1", "lines", List.of("x", "y")));

        @SuppressWarnings("unchecked")
        Map<String, Object> read = codec.deserialize(payload, Map.class);

        assertThat(read).containsEntry("id", "A-1").containsEntry("lines", List.of("x", "y"));
    }

    /**
     * The case this error message exists for: a deployment changed a task's declared external type
     * while rows were still staged under the old one.
     */
    @Test
    void readingAPayloadAsTheWrongTypeFailsLoudlyRatherThanReturningNull() {
        assertThatExceptionOfType(ReconciliationSerializationException.class)
                .isThrownBy(() -> codec.deserialize("{\"id\":\"A-1\"}", Integer.class))
                .withMessageContaining("external type may have changed");
    }

    @Test
    void anUnserializableRecordIsAProgrammingErrorAndSaysSo() {
        assertThatExceptionOfType(ReconciliationSerializationException.class)
                .isThrownBy(() -> codec.serialize(new Object()))
                .withMessageContaining("Could not serialize");
    }
}
