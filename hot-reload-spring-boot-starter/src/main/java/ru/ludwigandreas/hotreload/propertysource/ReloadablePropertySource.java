package ru.ludwigandreas.hotreload.propertysource;

import org.springframework.core.env.EnumerablePropertySource;

import java.util.Map;

/**
 * A {@link org.springframework.core.env.PropertySource} whose content can be swapped at runtime.
 * Registered once (empty) as early as {@link ru.ludwigandreas.hotreload.config.HotReloadEnvironmentPostProcessor}
 * runs, so it's already present - in the right precedence slot - by the time any {@code @ConfigurationProperties}
 * bean binds; {@link #update(Map)} is then called every time the backing source reloads.
 *
 * <p>{@link #getProperty(String)} and {@link #getPropertyNames()} read a single volatile reference, so
 * a reload never exposes a torn/partial view of the property set to a concurrent reader.
 */
public class ReloadablePropertySource extends EnumerablePropertySource<Map<String, Object>> {

    private volatile Map<String, Object> content;

    public ReloadablePropertySource(String name) {
        this(name, Map.of());
    }

    public ReloadablePropertySource(String name, Map<String, Object> initialContent) {
        super(name, Map.copyOf(initialContent));
        this.content = Map.copyOf(initialContent);
    }

    public void update(Map<String, Object> newContent) {
        this.content = Map.copyOf(newContent);
    }

    @Override
    public Object getProperty(String name) {
        return content.get(name);
    }

    @Override
    public boolean containsProperty(String name) {
        return content.containsKey(name);
    }

    @Override
    public String[] getPropertyNames() {
        return content.keySet().toArray(new String[0]);
    }

    @Override
    public Map<String, Object> getSource() {
        return content;
    }
}
