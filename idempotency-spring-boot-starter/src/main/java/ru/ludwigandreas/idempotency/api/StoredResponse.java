package ru.ludwigandreas.idempotency.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The response a completed claim replays.
 *
 * <p>This is the part a bare dedup flag cannot do, and it is what makes the feature real for an API:
 * a caller who retried a timed-out {@code POST} needs the original {@code 201} and its body, not a
 * boolean saying "you already did this". A flag tells a caller that something happened; it does not
 * tell them what was created, where it is, or what its id was - all of which were in the response
 * that the timeout ate.
 *
 * <p>Only the headers worth replaying are kept, and the decision is the filter's rather than this
 * record's - see {@code IdempotencyProperties.Http}. Replaying every header would mean replaying
 * {@code Date}, {@code Set-Cookie} and whatever a proxy added, which at best is noise and at worst
 * hands a second caller the first caller's session.
 *
 * @param status      the HTTP status the original response carried
 * @param contentType the original {@code Content-Type}, or {@code null} for a response with no body
 * @param headers     the headers worth replaying, in the order they were captured
 * @param body        the response body as bytes. Bytes rather than a string because a body is not
 *                    necessarily text and a replay has to be byte-for-byte - re-encoding it through a
 *                    {@code String} would change the {@code Content-Length} of anything outside
 *                    ASCII, and the point of a replay is that the caller cannot tell it from the
 *                    original
 */
public record StoredResponse(int status, String contentType, Map<String, String> headers, byte[] body) {

    /** An empty body, so that a response with none does not need a null check at every use. */
    private static final byte[] NO_BODY = new byte[0];

    /** The lowest number that is an HTTP status at all. */
    private static final int MIN_STATUS = 100;

    /** One past the highest three-digit status. */
    private static final int MAX_STATUS = 599;

    /** Normalises the headers and the body, and refuses a status that is not one. */
    public StoredResponse {
        if (status < MIN_STATUS || status > MAX_STATUS) {
            throw new IllegalArgumentException("Not an HTTP status: " + status);
        }
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? NO_BODY : body.clone();
    }

    /**
     * A response with a body and no extra headers.
     *
     * @param status      the status
     * @param contentType the content type
     * @param body        the body bytes
     * @return the response
     */
    public static StoredResponse of(int status, String contentType, byte[] body) {
        return new StoredResponse(status, contentType, Map.of(), body);
    }

    /** This response with one more header to replay. */
    public StoredResponse withHeader(String name, String value) {
        if (name == null || value == null) {
            return this;
        }
        Map<String, String> merged = new LinkedHashMap<>(headers);
        merged.put(name, value);
        return new StoredResponse(status, contentType, merged, body);
    }

    /**
     * The body bytes.
     *
     * <p>A defensive copy, like the one the constructor takes. A record's accessor handing out the
     * array itself would make this value mutable by anybody who has read it once, and what is at
     * stake is a response that has already been sent to somebody.
     *
     * @return the body
     */
    @Override
    public byte[] body() {
        return body.clone();
    }

    /** How many bytes the body is, without copying it. */
    public int bodyLength() {
        return body.length;
    }
}
