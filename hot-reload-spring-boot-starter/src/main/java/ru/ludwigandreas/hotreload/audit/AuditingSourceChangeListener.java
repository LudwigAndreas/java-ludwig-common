package ru.ludwigandreas.hotreload.audit;

import ru.ludwigandreas.hotreload.core.SourceChangeEvent;
import ru.ludwigandreas.hotreload.core.SourceChangeListener;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns every {@link SourceChangeEvent} into a {@link HotReloadAuditEntry} and hands it to the
 * configured {@link HotReloadAuditLogger} - the "who/when/old/new" trail. Sensitive values are redacted
 * (see {@link SecretRedaction}) before the entry is built, so no {@link HotReloadAuditLogger}
 * implementation ever sees real secret material.
 */
public class AuditingSourceChangeListener implements SourceChangeListener {

    private final HotReloadAuditLogger auditLogger;
    private final String actor;

    public AuditingSourceChangeListener(HotReloadAuditLogger auditLogger, String actor) {
        this.auditLogger = auditLogger;
        this.actor = actor;
    }

    @Override
    public void onChange(SourceChangeEvent event) {
        if (event.changedKeys().isEmpty()) {
            return;
        }
        Map<String, HotReloadAuditEntry.ValueChange> changes = new LinkedHashMap<>();
        for (String key : event.changedKeys()) {
            Object oldValue = SecretRedaction.redactIfSensitive(event.sourceId(), key, event.oldValueOf(key));
            Object newValue = SecretRedaction.redactIfSensitive(event.sourceId(), key, event.newValueOf(key));
            changes.put(key, new HotReloadAuditEntry.ValueChange(oldValue, newValue));
        }
        auditLogger.record(new HotReloadAuditEntry(event.sourceId(), Instant.now(), actor, changes));
    }

    @Override
    public void onError(String sourceId, Exception exception) {
        auditLogger.recordError(sourceId, Instant.now(), actor, exception);
    }
}
