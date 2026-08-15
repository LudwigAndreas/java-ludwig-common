package ru.ludwigandreas.hotreload.audit;

import java.time.Instant;
import java.util.Map;

/**
 * One reload, fully attributed: which source, when, which application instance observed and applied it
 * ({@link #actor()}), and the old/new value of every key that changed. Built by {@link
 * AuditingSourceChangeListener} from a {@link ru.ludwigandreas.hotreload.core.SourceChangeEvent} and
 * handed to whatever {@link HotReloadAuditLogger} is configured.
 *
 * <p>{@link #actor()} identifies <em>this application instance</em> (hostname by default, overridable
 * via {@code ludwig.hotreload.audit.actor}) - it is <strong>not</strong> the upstream identity that made
 * the underlying change (the person/process that wrote a Vault secret or edited a ConfigMap). Neither a
 * mounted file nor a plain Vault read exposes that; Vault's own audit device is the source of truth for
 * "who changed the secret", and this entry is the complementary "who/when noticed and applied it" record
 * on the consuming side.
 */
public record HotReloadAuditEntry(String sourceId, Instant timestamp, String actor, Map<String, ValueChange> changes) {

    public HotReloadAuditEntry {
        changes = Map.copyOf(changes);
    }

    /**
     * A single key's before/after value, already redacted (see {@link AuditingSourceChangeListener}) for
     * anything originating from Vault or matching a common secret-key pattern - {@link HotReloadAuditLogger}
     * implementations never see real secret material.
     */
    public record ValueChange(Object oldValue, Object newValue) {
    }
}
