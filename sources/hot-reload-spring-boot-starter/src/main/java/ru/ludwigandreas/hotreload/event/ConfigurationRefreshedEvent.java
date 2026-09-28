package ru.ludwigandreas.hotreload.event;

import org.springframework.context.ApplicationEvent;

import java.util.Set;

/**
 * Published on the application context whenever a hot-reloadable source (file or Vault secret) has
 * changed and its content has been pushed into the {@code Environment}. Application code can listen for
 * this generically instead of - or in addition to - relying on a {@link
 * ru.ludwigandreas.hotreload.binding.RefreshableConfig} to re-bind a specific {@code @ConfigurationProperties}
 * type.
 */
public class ConfigurationRefreshedEvent extends ApplicationEvent {

    private final String sourceId;
    private final Set<String> changedKeys;

    public ConfigurationRefreshedEvent(Object source, String sourceId, Set<String> changedKeys) {
        super(source);
        this.sourceId = sourceId;
        this.changedKeys = Set.copyOf(changedKeys);
    }

    /** Id of the {@link ru.ludwigandreas.hotreload.core.ReloadableSource} that changed. */
    public String getSourceId() {
        return sourceId;
    }

    public Set<String> getChangedKeys() {
        return changedKeys;
    }
}
