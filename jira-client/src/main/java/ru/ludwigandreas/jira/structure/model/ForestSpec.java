package ru.ludwigandreas.jira.structure.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A Structure forest specification: what content to produce, before any adjustment is applied.
 *
 * <p>Every Structure read and write is addressed by one of these rather than by a structure id, because
 * Structure's unit of content is a forest and a structure is only the commonest source of one. The others
 * matter in practice: {@code skeleton} asks for a structure without its dynamically generated rows, and
 * {@code type: "clipboard"} addresses the user's clipboard, which is where a newly created item lands when
 * it is not being put into a structure.
 *
 * <p>Held as an open property map rather than a fixed record, and serialized as that map directly. The
 * specification language is Structure's, it is larger than the handful of forms this client names, and it
 * grows with the app - so a closed model would be a ceiling. The named factories cover the common cases;
 * {@link #with(String, Object)} reaches everything else without dropping out of the type system into raw
 * JSON.
 */
public final class ForestSpec {

    private final Map<String, Object> properties;

    private ForestSpec(Map<String, Object> properties) {
        // Insertion-ordered rather than Map.copyOf, which returns a HashMap and randomizes the key order.
        // A specification that serializes differently on every JVM start makes a logged payload, a recorded
        // fixture and a cache key all useless for comparison.
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    /**
     * A specification from its raw properties, which is also how one is deserialized from a response.
     *
     * @param properties the specification's JSON properties
     * @return the specification
     */
    @JsonCreator
    public static ForestSpec ofProperties(Map<String, Object> properties) {
        return new ForestSpec(properties == null ? Map.of() : properties);
    }

    /** The whole content of a structure. */
    public static ForestSpec structure(long structureId) {
        return new ForestSpec(Map.of("structureId", structureId));
    }

    /** A structure's manually arranged skeleton, without the rows its generators produce. */
    public static ForestSpec skeleton(long structureId) {
        return new ForestSpec(Map.of("skeleton", structureId));
    }

    /** The calling user's Structure clipboard. */
    public static ForestSpec clipboard() {
        return new ForestSpec(Map.of("type", "clipboard"));
    }

    /** The specification's properties, and what gets serialized for it. */
    @JsonValue
    public Map<String, Object> properties() {
        return properties;
    }

    /** The structure this specification names, when it names one. */
    public Optional<Long> structureId() {
        Object value = properties.get("structureId");
        return value instanceof Number number ? Optional.of(number.longValue()) : Optional.empty();
    }

    /**
     * This specification with an extra property.
     *
     * @param property the JSON property name
     * @param value the value, serialized by Jackson
     * @return a new specification carrying the property
     */
    public ForestSpec with(String property, Object value) {
        Map<String, Object> extended = new LinkedHashMap<>(properties);
        extended.put(property, value);
        return new ForestSpec(extended);
    }

    /**
     * This specification with a transformation appended - a sort, a grouping, a filter applied to the base
     * content after it is produced.
     *
     * @param transformation the transformation's JSON properties
     * @return a new specification carrying the transformation
     */
    @SuppressWarnings("unchecked")
    public ForestSpec withTransformation(Map<String, Object> transformation) {
        List<Object> existing = properties.get("transformations") instanceof List<?> list
                ? new ArrayList<>((List<Object>) list)
                : new ArrayList<>();
        existing.add(transformation);
        return with("transformations", List.copyOf(existing));
    }

    @Override
    public String toString() {
        return "ForestSpec" + properties;
    }
}
