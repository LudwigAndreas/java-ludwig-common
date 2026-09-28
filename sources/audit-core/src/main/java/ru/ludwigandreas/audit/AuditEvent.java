package ru.ludwigandreas.audit;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;

/**
 * One thing that happened, in the one shape every sink in this platform receives.
 *
 * <p>Derived from {@code security-spring-boot-starter}'s {@code AccessDecision}, which was already
 * the closest thing here to a universal envelope - actor, resource type, resource id, action,
 * outcome, reason - rather than invented from nothing. Nine modules used to have nine of these, no
 * two alike, which meant an auditor asking "show me everything this person did last Tuesday" had
 * nine places to look in three formats.
 *
 * <h2>Two rules that hold for every event, everywhere</h2>
 *
 * <p><b>{@link #attributes()} is redacted at construction, not at the sink.</b>
 * {@code AuditingSourceChangeListener} in the hot-reload module established this and said why:
 * redaction is "deliberately applied at the point the audit entry is built, not left to each logger
 * implementation, so a custom logger persisting to a database or shipping to a SIEM can't forget to
 * do it." That is now platform-wide. A sink that can receive an unredacted event is a sink that will
 * eventually write one somewhere permanent.
 *
 * <p><b>Module events are subtypes of this envelope in spirit, not replacements for their own
 * types.</b> {@code ExportAuditEvent}, {@code OutboundCallAudit}, {@code AccessDecision} and the rest
 * keep their typed shape at the call site - a caller assembling thirteen positional components out
 * of a {@code Map<String, Object>} is worse than what those records already are - and each gains a
 * {@code toAuditEvent()} that flattens into this. The typed record is the authoring surface; this is
 * the transport and storage surface.
 *
 * @param id            assigned at construction when the caller does not supply one, and the
 *                      idempotency key for shipping the event off-platform, so a redelivery is
 *                      recognisable as the same event rather than as a second thing that happened
 * @param occurredAt    when it happened; never null, so a trail cannot contain an event with no
 *                      position in time
 * @param category      which subsystem it came from, e.g. {@code export}, {@code access},
 *                      {@code settings} - see {@link AuditCategories}
 * @param action        what happened, dotted and stable, e.g. {@code run.completed},
 *                      {@code access.denied}, {@code setting.changed}; part of the contract a sink
 *                      and a SIEM rule may branch on
 * @param actor         who did it
 * @param resource      what it was about
 * @param outcome       how it turned out
 * @param correlationId ties this event to the logs, traces and partner calls of the same unit of work
 * @param traceId       the W3C trace id, when a trace was sampled
 * @param attributes    everything module-specific, <strong>already redacted</strong>
 */
@Builder(toBuilder = true)
public record AuditEvent(
        UUID id,
        Instant occurredAt,
        String category,
        String action,
        Actor actor,
        Resource resource,
        AuditOutcome outcome,
        String correlationId,
        String traceId,
        Map<String, Object> attributes) {

    /**
     * Normalises and validates the envelope.
     *
     * <p>Every existing module record's validation is kept rather than relaxed to the weakest of
     * them: {@code ExportAuditEvent} refused a null timestamp and everyone defensively copied their
     * details map, and an audit event with no time or a caller-mutable attribute map must stay
     * impossible to construct.
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a record's canonical constructor. An audit envelope is
    // wide by nature and every component is named at the call site by the generated builder.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public AuditEvent {
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("An AuditEvent needs a category");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("An AuditEvent needs an action");
        }
        id = id == null ? UUID.randomUUID() : id;
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        actor = actor == null ? Actor.system() : actor;
        outcome = outcome == null ? AuditOutcome.success() : outcome;
        attributes = attributes == null ? Map.of() : copyWithoutNulls(attributes);
    }

    /**
     * The event with {@code key} added to its attributes.
     *
     * <p>The value is assumed already redacted, like everything else in the map - this is a
     * convenience for assembling an envelope, not a second place where sensitivity is decided.
     *
     * @param key   the attribute name
     * @param value the attribute value, or {@code null} to leave the map untouched
     * @return a new event; this record is immutable
     */
    public AuditEvent with(String key, Object value) {
        if (value == null) {
            return this;
        }
        Map<String, Object> merged = new LinkedHashMap<>(attributes);
        merged.put(key, value);
        return toBuilder().attributes(merged).build();
    }

    /**
     * A null-tolerant, order-preserving copy.
     *
     * <p>{@code Map.copyOf} rejects a null value, and the module records feeding this envelope are
     * full of genuinely optional facts - a run with no saved report id, a call that received no
     * response. Dropping the key is the right answer for an audit attribute: "absent" and
     * "present and null" mean the same thing to every reader of the trail, and the alternative is a
     * caller having to filter its own map before every construction.
     *
     * <p>Wrapped unmodifiable rather than passed through {@code Map.copyOf}, which discards
     * iteration order: the attributes reach a log line and a JSON document in the order the module
     * assembled them, and a reader comparing two events of the same action expects the same fields
     * in the same places.
     */
    private static Map<String, Object> copyWithoutNulls(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && value != null) {
                copy.put(key, value);
            }
        });
        return Collections.unmodifiableMap(copy);
    }
}
