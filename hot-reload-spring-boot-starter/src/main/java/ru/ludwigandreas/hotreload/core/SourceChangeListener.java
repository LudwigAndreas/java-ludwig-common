package ru.ludwigandreas.hotreload.core;

/** Notified by a watcher whenever a watched {@link ReloadableSource} changes (or fails to reload). */
@FunctionalInterface
public interface SourceChangeListener {

    void onChange(SourceChangeEvent event);

    /**
     * A reload attempt failed (I/O error, parse error, Vault unreachable, ...). Default implementation
     * ignores the failure - override to log/alert/fall back to last-known-good, which the watcher
     * already does on the caller's behalf (a failed reload never clears previously published content).
     */
    default void onError(String sourceId, Exception exception) {
        // no-op by default; watchers keep serving the last successfully loaded content
    }
}
