package ru.ludwigandreas.hotreload.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.audit.redaction.Redactor;
import ru.ludwigandreas.hotreload.core.SourceChangeEvent;
import ru.ludwigandreas.hotreload.core.SourceChangeListener;

/**
 * Turns every {@link SourceChangeEvent} into a {@link HotReloadAuditEntry} and hands it to the platform's
 * {@link AuditSink} - the "who/when/old/new" trail.
 *
 * <p>Sensitive values are redacted <em>before the entry is built</em>, so no sink implementation ever sees
 * real secret material. That rule started here and is now platform-wide: a custom sink persisting to a
 * database or shipping to a SIEM cannot forget to redact if it never receives the value.
 *
 * <p>The classification is the platform's composed {@link Redactor}, which keeps both of this module's
 * original rules - the secret-name heuristic and "anything from Vault, whatever it is called" - and adds a
 * deployment's configured names on top. {@code ludwig.audit.redaction.sensitive-provenance-prefixes} is
 * where the {@code vault:} and {@code vault-lease:} prefixes now live; a deployment with a differently
 * named secret store adds its own there rather than patching this module.
 */
public class AuditingSourceChangeListener implements SourceChangeListener {

    private final AuditSink auditSink;
    private final Redactor redactor;
    private final String actor;

    /**
     * Creates the listener.
     *
     * @param auditSink where entries go
     * @param redactor  decides and applies masking, before the entry exists
     * @param actor     identifies this application instance, not the author of the upstream change
     */
    public AuditingSourceChangeListener(AuditSink auditSink, Redactor redactor, String actor) {
        this.auditSink = auditSink;
        this.redactor = redactor;
        this.actor = actor;
    }

    @Override
    public void onChange(SourceChangeEvent event) {
        if (event.changedKeys().isEmpty()) {
            return;
        }
        Map<String, HotReloadAuditEntry.ValueChange> changes = new LinkedHashMap<>();
        for (String key : event.changedKeys()) {
            // The source id is the provenance argument, which is what makes "every Vault-sourced key is
            // sensitive regardless of its name" expressible as a classifier rather than a special case.
            Object oldValue = redactor.redactValue(event.sourceId(), key, event.oldValueOf(key));
            Object newValue = redactor.redactValue(event.sourceId(), key, event.newValueOf(key));
            changes.put(key, new HotReloadAuditEntry.ValueChange(oldValue, newValue));
        }
        auditSink.record(new HotReloadAuditEntry(event.sourceId(), Instant.now(), actor, changes)
                .toAuditEvent());
    }

    @Override
    public void onError(String sourceId, Exception exception) {
        auditSink.record(HotReloadAuditEntry.failureEvent(sourceId, Instant.now(), actor, exception));
    }
}
