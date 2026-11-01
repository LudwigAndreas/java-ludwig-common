package ru.ludwigandreas.hotreload.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Every reloadable source this replica watches, by id, filled where each source is created. It is what lets a source
 * be reloaded on demand - by the administrative {@code hotreloadreload} control - through the same
 * {@link SourceReloadCoordinator} a watcher uses, so a forced reload is indistinguishable from a watched one.
 */
public class ReloadableSourceRegistry {

    /** What a source is read from. */
    public enum Kind {
        /** A properties or YAML file. */
        FILE,
        /** A Vault KV secret, polled. */
        VAULT_KV,
        /** A Vault dynamic secret, delivered by its lease; a forced reload requests a new one. */
        VAULT_LEASE,
        /** A FreeMarker template directory. */
        TEMPLATE_DIRECTORY
    }

    /**
     * One registered source.
     *
     * @param id     the source's id
     * @param kind   what it is read from
     * @param reload reloads it now, on this replica
     */
    public record Entry(String id, Kind kind, Supplier<ReloadOutcome> reload) {
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /**
     * Registers a source reloaded by reading it again.
     *
     * @param kind        what it is read from
     * @param source      the source
     * @param coordinator the coordinator its watcher uses
     */
    public void register(Kind kind, ReloadableSource source, SourceReloadCoordinator coordinator) {
        register(new Entry(source.id(), kind, () -> coordinator.reloadOutcome(source)));
    }

    /**
     * Registers a source with its own way of reloading.
     *
     * @param entry the source
     */
    public synchronized void register(Entry entry) {
        entries.putIfAbsent(entry.id(), entry);
    }

    /**
     * The registered sources, in registration order.
     *
     * @return them
     */
    public synchronized List<Entry> entries() {
        return new ArrayList<>(entries.values());
    }

    /**
     * One registered source.
     *
     * @param id its id
     * @return it, when registered
     */
    public synchronized Optional<Entry> find(String id) {
        return Optional.ofNullable(entries.get(id));
    }
}
