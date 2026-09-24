package ru.ludwigandreas.reconciliation.payload;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.ludwigandreas.reconciliation.exception.ReconciliationSerializationException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Turns an external record into the two things staging needs: the JSON that goes in the
 * {@code payload} column, and the hash that decides whether applying it would change anything.
 *
 * <h2>Why the hash is taken over a normalized form</h2>
 *
 * <p>Because the point of the hash is to answer "is this the same record as last time?", and most
 * partners cannot be relied on to serialize identically twice. JSON object key order is not
 * significant and many servers do not stabilize it; a record re-serialized with its fields in a
 * different order is byte-different and semantically identical. Hashing the raw bytes would report
 * every such record as changed, which means a write, an {@code updated_at} bump, an audit row and a
 * downstream event for a record that did not change - on every poll, for as long as the integration
 * lives.
 *
 * <p>So object keys are sorted recursively before hashing. Array order is <em>not</em> touched:
 * arrays are ordered by definition, and a partner that reorders a list has told us something
 * different, even if the set of elements is the same.
 *
 * <p>The stored payload keeps the partner's own serialization, unsorted. The audit trail is supposed
 * to show what the partner actually said, not a tidied version of it.
 */
public class PayloadCodec {

    private static final String HASH_ALGORITHM = "SHA-256";

    private final ObjectMapper objectMapper;

    /**
     * Creates the codec.
     *
     * @param objectMapper mapper used for both directions; should be the application's, so that a
     *                     partner's date format is read the same way here as everywhere else
     */
    public PayloadCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Serializes an external record for the {@code payload} column.
     *
     * @param record the external record
     * @return its JSON form
     * @throws ReconciliationSerializationException if the record cannot be serialized, which is a
     *                                             programming error in the fetcher rather than a
     *                                             partner problem and must not be retried
     */
    public String serialize(Object record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (JsonProcessingException e) {
            throw new ReconciliationSerializationException(
                    "Could not serialize external record of type " + record.getClass().getName(), e);
        }
    }

    /**
     * Reads a staged payload back.
     *
     * @param <O>     the external record type
     * @param payload the stored JSON
     * @param type    the task's declared external type
     * @return the record
     * @throws ReconciliationSerializationException if the stored payload no longer matches the task's
     *                                             external type - which happens when a deployment
     *                                             changes that type while rows are still staged, and
     *                                             is worth an explicit error rather than a null
     */
    public <O> O deserialize(String payload, Class<O> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (JsonProcessingException e) {
            throw new ReconciliationSerializationException(
                    "Could not read a staged payload as " + type.getName()
                            + "; the task's external type may have changed while rows were staged", e);
        }
    }

    /**
     * Hashes an external record for the idempotency short-circuit.
     *
     * @param record the external record
     * @return a lowercase hex SHA-256 of the normalized JSON
     */
    public String hash(Object record) {
        return hashJson(serialize(record));
    }

    /**
     * Hashes an already-serialized payload.
     *
     * @param payload the JSON form
     * @return a lowercase hex SHA-256 of its normalized form
     */
    public String hashJson(String payload) {
        try {
            JsonNode normalized = normalize(objectMapper.readTree(payload));
            byte[] digest = MessageDigest.getInstance(HASH_ALGORITHM)
                    .digest(objectMapper.writeValueAsBytes(normalized));
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException e) {
            throw new ReconciliationSerializationException("Could not normalize a payload for hashing", e);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JRE; this cannot happen on a conformant platform.
            throw new IllegalStateException(HASH_ALGORITHM + " is not available", e);
        }
    }

    /** Recursively sorts object keys; leaves array order alone. */
    private JsonNode normalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
            node.fields().forEachRemaining(fields::add);
            fields.sort(Comparator.comparing(Map.Entry::getKey));
            fields.forEach(entry -> sorted.set(entry.getKey(), normalize(entry.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode copy = objectMapper.createArrayNode();
            node.forEach(element -> copy.add(normalize(element)));
            return copy;
        }
        return node;
    }
}
