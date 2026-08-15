package ru.ludwigandreas.hotreload.core;

import java.util.Map;

/**
 * Anything that can be (re)loaded into a flat key/value view: a properties file, a YAML file, a Vault
 * KV secret, a FreeMarker template on disk. {@link #load()} must be safe to call repeatedly and is
 * expected to return the current state each time - implementations don't need to be diff-aware
 * themselves, that's {@link ru.ludwigandreas.hotreload.core.watch.PollingResourceWatcher}'s job.
 */
public interface ReloadableSource {

    /** Stable identifier used in logs/events and as the key into any registry of sources. */
    String id();

    /** Loads (or reloads) this source's current content. Throws on read/parse failure. */
    Map<String, Object> load() throws Exception;
}
