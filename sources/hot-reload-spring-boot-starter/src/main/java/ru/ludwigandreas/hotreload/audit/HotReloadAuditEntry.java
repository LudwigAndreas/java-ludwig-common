package ru.ludwigandreas.hotreload.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import ru.ludwigandreas.audit.Actor;
import ru.ludwigandreas.audit.AuditCategories;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditOutcome;
import ru.ludwigandreas.audit.Resource;

/**
 * One reload, fully attributed: which source, when, which application instance observed and applied it
 * ({@link #actor()}), and the old/new value of every key that changed. Built by {@link
 * AuditingSourceChangeListener} from a {@link ru.ludwigandreas.hotreload.core.SourceChangeEvent} and
 * flattened into the platform envelope by {@link #toAuditEvent()}.
 *
 * <p>{@link #actor()} identifies <em>this application instance</em> (hostname by default, overridable
 * via {@code ludwig.hotreload.audit.actor}) - it is <strong>not</strong> the upstream identity that made
 * the underlying change (the person/process that wrote a Vault secret or edited a ConfigMap). Neither a
 * mounted file nor a plain Vault read exposes that; Vault's own audit device is the source of truth for
 * "who changed the secret", and this entry is the complementary "who/when noticed and applied it" record
 * on the consuming side. It is therefore recorded with {@code principalType} {@code INSTANCE} rather than
 * left to look like a user - an audit trail in which a hostname sits in the same column as a person's id
 * with nothing to tell them apart is a trail that will be read wrong.
 */
public record HotReloadAuditEntry(String sourceId, Instant timestamp, String actor,
                                  Map<String, ValueChange> changes) {

    /** A reload was applied and changed at least one key. */
    public static final String ACTION_APPLIED = "config.reloaded";

    /** A reload attempt failed; the previous, valid content kept serving. */
    public static final String ACTION_FAILED = "config.reload-failed";

    /** What {@link Actor#principalType()} carries for an application instance rather than a person. */
    public static final String PRINCIPAL_TYPE = "INSTANCE";

    public HotReloadAuditEntry {
        changes = Map.copyOf(changes);
    }

    /**
     * A single key's before/after value, already redacted (see {@link AuditingSourceChangeListener}) for
     * anything originating from Vault or matching a common secret-key pattern - no audit sink ever sees
     * real secret material.
     */
    public record ValueChange(Object oldValue, Object newValue) {
    }

    /**
     * This entry as a platform audit event.
     *
     * <p>The changes become one attribute per key, each a two-entry map of {@code old} and {@code new},
     * rather than one event per key. A reload is one thing that happened - somebody edited a ConfigMap
     * once - and splitting it into N events would make "what did that reload change" a correlation
     * problem instead of a single row. {@code Slf4jHotReloadAuditLogger} did log one line per key; that
     * is the one behaviour this migration deliberately changes, and the module's README says so.
     *
     * @return the event, with every value already redacted
     */
    public AuditEvent toAuditEvent() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        Map<String, Object> changed = new LinkedHashMap<>();
        changes.forEach((key, change) -> {
            Map<String, Object> pair = new LinkedHashMap<>();
            pair.put("old", change.oldValue());
            pair.put("new", change.newValue());
            changed.put(key, pair);
        });
        attributes.put("changedKeys", changes.keySet().stream().sorted().toList());
        attributes.put("changes", changed);
        return AuditEvent.builder()
                .category(AuditCategories.CONFIG)
                .action(ACTION_APPLIED)
                .occurredAt(timestamp)
                .actor(new Actor(actor, PRINCIPAL_TYPE, null, null))
                .resource(Resource.ofType(sourceId))
                .outcome(AuditOutcome.success())
                .attributes(attributes)
                .build();
    }

    /**
     * The event for a reload that failed.
     *
     * <p>A static factory rather than an entry with no changes, because a failed reload has no old/new
     * values to carry and constructing an empty {@code changes} map to describe one would make "nothing
     * changed" and "it broke" the same shape.
     *
     * @param sourceId  the source that could not be reloaded
     * @param timestamp when the attempt failed
     * @param actor     this application instance
     * @param exception why it failed
     * @return the event
     */
    public static AuditEvent failureEvent(String sourceId, Instant timestamp, String actor,
                                          Exception exception) {
        return AuditEvent.builder()
                .category(AuditCategories.CONFIG)
                .action(ACTION_FAILED)
                .occurredAt(timestamp)
                .actor(new Actor(actor, PRINCIPAL_TYPE, null, null))
                .resource(Resource.ofType(sourceId))
                // The exception's message and not its stack trace: the trail records that the reload
                // failed and the application log records how. A stack trace in a row retained for years
                // is a copy of internal structure nobody reading the trail is entitled to.
                .outcome(AuditOutcome.failure(exception == null ? null : exception.toString()))
                .build();
    }
}
