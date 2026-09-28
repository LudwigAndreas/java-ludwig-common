package ru.ludwigandreas.audit;

/**
 * What an audit event was about.
 *
 * <p>Three fields and no payload. An audit trail is retained for years and read by people who are
 * not entitled to the data it guards, so it records which object was touched and never what was in
 * it - the rule {@code AccessDecision} and {@code OutboundCallAudit} already state, generalised.
 *
 * @param type the kind of thing: an entity name, a report definition key, a setting key, a task name
 * @param id   the particular thing, or {@code null} for an event about a set rather than an object -
 *             a query-level authorization decision whose answer was a predicate, a whole-profile read
 * @param name a human-readable label for the resource, when the id alone would mean nothing to a
 *             reader years later
 */
public record Resource(String type, String id, String name) {

    /** A resource identified by type alone, for an event about a set rather than an object. */
    public static Resource ofType(String type) {
        return new Resource(type, null, null);
    }

    /** A resource identified by type and id, which is the common case. */
    public static Resource of(String type, String id) {
        return new Resource(type, id, null);
    }
}
