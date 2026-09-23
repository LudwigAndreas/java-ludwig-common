package ru.ludwigandreas.reconciliation.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.ludwigandreas.reconciliation.exception.ReconciliationSerializationException;

import java.util.List;

/**
 * Reads and writes the {@code demand_keys} column: the correlation keys one remote job covers.
 *
 * <p>Held on the job row rather than recomputed, because it is what has to be excluded from the next
 * submit pass - and by the time that pass runs, the demand query may well answer differently. A job
 * whose keys were recomputed could resubmit work it is already doing, which for a billable export is
 * the expensive kind of wrong.
 */
public class DemandKeys {

    private static final TypeReference<List<String>> KEY_LIST = new TypeReference<>() { };

    private final ObjectMapper objectMapper;

    /**
     * Creates the codec.
     *
     * @param objectMapper the module's object mapper
     */
    public DemandKeys(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Renders keys for storage.
     *
     * @param keys the encoded correlation keys
     * @return their JSON array form
     */
    public String write(List<String> keys) {
        try {
            return objectMapper.writeValueAsString(keys);
        } catch (JsonProcessingException e) {
            throw new ReconciliationSerializationException("Could not serialize a job's demand keys", e);
        }
    }

    /**
     * Reads stored keys.
     *
     * @param json the stored JSON array, possibly null for a job written before this column was used
     * @return the encoded correlation keys, empty when there are none
     */
    public List<String> read(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, KEY_LIST);
        } catch (JsonProcessingException e) {
            throw new ReconciliationSerializationException("Could not read a job's demand keys", e);
        }
    }
}
