package ru.ludwigandreas.jira.http;

import java.nio.charset.StandardCharsets;

/**
 * A request payload that has already been rendered to bytes, together with the {@code Content-Type} that
 * describes them.
 *
 * <p>The transport deals only in bytes. Serialization happens one layer up, in the REST client, so that a
 * different transport - a test double, an Apache HttpClient adapter, a corporate proxy wrapper - never
 * needs to know that Jackson exists, and so that a failure to serialize is reported as a serialization
 * failure rather than as a transport failure.
 *
 * @param contentType value for the {@code Content-Type} header
 * @param content the payload bytes
 */
public record RequestBody(String contentType, byte[] content) {

    /** JSON media type used for every Jira REST call that carries a body. */
    public static final String APPLICATION_JSON = "application/json";

    /** Wraps a UTF-8 JSON document. */
    public static RequestBody json(String json) {
        return new RequestBody(APPLICATION_JSON + "; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    /** Wraps pre-rendered bytes with an explicit media type. */
    public static RequestBody of(String contentType, byte[] content) {
        return new RequestBody(contentType, content.clone());
    }

    /** Defensive copy: the array must not be mutated after the request is built. */
    public RequestBody {
        content = content.clone();
    }

    /** A copy of the payload bytes. */
    @Override
    public byte[] content() {
        return content.clone();
    }

    /** Payload size in bytes, for logging and metrics without copying the array. */
    public int size() {
        return content.length;
    }
}
