package ru.ludwigandreas.jira.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import ru.ludwigandreas.jira.error.JiraSerializationException;

/**
 * The client's JSON codec: one configured {@link ObjectMapper} plus read/write helpers that turn Jackson's
 * exceptions into {@link JiraSerializationException} with enough context to diagnose them.
 *
 * <p>The mapper's configuration is not negotiable for a Jira client, and each setting answers a specific
 * failure:
 *
 * <ul>
 *   <li><b>Unknown properties are ignored.</b> Jira adds response fields in point releases and every
 *       marketplace app adds more. A model that fails on an unrecognised key turns a Jira upgrade into an
 *       outage of every integration.</li>
 *   <li><b>Nulls are omitted on write.</b> Jira's update semantics read "absent" as "leave alone"; a
 *       serialized {@code "priority": null} means "clear the priority". Writing every unset property as
 *       null would silently wipe fields the caller never mentioned.</li>
 *   <li><b>Dates are strings, not epoch numbers</b>, in Jira's own dialect - see {@link JiraTimeModule}.</li>
 *   <li><b>Empty beans do not fail serialization</b>, because a request object legitimately serializes to
 *       {@code {}} for endpoints whose payload is entirely optional.</li>
 * </ul>
 *
 * <p>A caller that needs the mapper - to register a module for a custom field codec, say - gets it from
 * {@link #objectMapper()}. Mutating it changes the client's behaviour, which is intended and is why the
 * client can also be built with a mapper supplied from outside.
 */
public final class JiraJson {

    /** How much of an unparseable body is quoted back in the exception message. */
    private static final int EXCERPT_LIMIT = 512;

    private final ObjectMapper objectMapper;

    public JiraJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** A mapper configured as described on this class. Each call returns a new, independently mutable one. */
    public static ObjectMapper defaultObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(new JiraTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    /** A codec over {@link #defaultObjectMapper()}. */
    public static JiraJson createDefault() {
        return new JiraJson(defaultObjectMapper());
    }

    /** The underlying mapper, for callers that need to register a module or reuse the configuration. */
    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    /**
     * Reads a response body into a concrete class.
     *
     * @param body the raw response bytes
     * @param type the target type
     * @param <T> the target type
     * @return the parsed value
     */
    public <T> T read(byte[] body, Class<T> type) {
        return read(body, objectMapper.getTypeFactory().constructType(type));
    }

    /**
     * Reads a response body into a generic type.
     *
     * @param body the raw response bytes
     * @param type the target type, captured through a {@link TypeReference}
     * @param <T> the target type
     * @return the parsed value
     */
    public <T> T read(byte[] body, TypeReference<T> type) {
        return read(body, objectMapper.getTypeFactory().constructType(type));
    }

    /**
     * Reads a response body into a resolved Jackson type.
     *
     * @param body the raw response bytes
     * @param type the resolved target type
     * @param <T> the target type
     * @return the parsed value
     */
    public <T> T read(byte[] body, JavaType type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (IOException e) {
            throw new JiraSerializationException(
                    "Could not read a " + type + " from the Jira response: " + excerpt(body), e);
        }
    }

    /** Parses a body into a raw tree, for endpoints whose shape is caller-defined. */
    public JsonNode readTree(byte[] body) {
        try {
            return objectMapper.readTree(body);
        } catch (IOException e) {
            throw new JiraSerializationException("Could not parse the Jira response as JSON: " + excerpt(body), e);
        }
    }

    /** Converts an already-parsed tree into a model type, used by the custom field codecs. */
    public <T> T convert(JsonNode node, JavaType type) {
        try {
            return objectMapper.readerFor(type).readValue(node);
        } catch (IOException e) {
            throw new JiraSerializationException("Could not convert " + node + " to " + type, e);
        }
    }

    /** Renders a request payload. */
    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new JiraSerializationException(
                    "Could not serialize a " + value.getClass().getName() + " as a Jira request body", e);
        }
    }

    private static String excerpt(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8).strip().replaceAll("\\s+", " ");
        return text.length() <= EXCERPT_LIMIT ? text : text.substring(0, EXCERPT_LIMIT) + "...";
    }
}
