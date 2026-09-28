package ru.ludwigandreas.idempotency.api;

/**
 * The scope names this platform's own ingresses claim under.
 *
 * <p>Constants rather than an enum, because a scope has to stay open: the HTTP surface scopes per
 * endpoint and a consumer scopes per topic, neither of which this module can enumerate. What the
 * constants buy is that the two generic ingresses spell their scope one way across every service, so
 * an operator looking at the table can tell an HTTP claim from a consumer claim without reading the
 * service's configuration.
 *
 * <p>A scope is at most 64 characters, because that is what the column is. It is truncated rather
 * than rejected nowhere: an over-long scope is a configuration mistake and fails on the insert, which
 * is the loud failure - a silent truncation would merge two scopes that were meant to be distinct,
 * which is the collision the whole scoping idea exists to prevent.
 */
public final class IdempotencyScopes {

    /** Keys that arrived in an HTTP {@code Idempotency-Key} header. */
    public static final String HTTP = "http";

    /** Keys derived from a Kafka record's coordinates or one of its headers. */
    public static final String KAFKA = "kafka";

    /**
     * The scope an HTTP claim uses when the filter is configured to scope per endpoint.
     *
     * <p>Per endpoint rather than one scope for the whole service, because the same key legitimately
     * means two things to two resources: a client that generates one key per user action and calls
     * two endpoints with it is not sending a duplicate, and a single scope would answer the second
     * call with the first one's response. The method is part of it for the same reason a
     * {@code DELETE} and a {@code POST} on one path are different asks.
     *
     * @param method the HTTP method
     * @param path   the mapped path, which should be the pattern rather than the resolved URI - a
     *              resolved URI would put an id in the scope and give every resource its own scope,
     *              which is a scope per row
     * @return the scope name
     */
    public static String forEndpoint(String method, String path) {
        return HTTP + ":" + method.toLowerCase(java.util.Locale.ROOT) + ":" + path;
    }

    /**
     * The scope a consumer claims a record under.
     *
     * @param topic the topic the record came from
     * @return the scope name
     */
    public static String forTopic(String topic) {
        return KAFKA + ":" + topic;
    }

    private IdempotencyScopes() {
    }
}
