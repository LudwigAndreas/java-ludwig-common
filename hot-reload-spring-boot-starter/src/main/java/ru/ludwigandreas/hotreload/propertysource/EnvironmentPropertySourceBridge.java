package ru.ludwigandreas.hotreload.propertysource;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import ru.ludwigandreas.hotreload.core.SourceChangeEvent;
import ru.ludwigandreas.hotreload.core.SourceChangeListener;
import ru.ludwigandreas.hotreload.event.ConfigurationRefreshedEvent;

/**
 * Bridges {@link ru.ludwigandreas.hotreload.core.SourceReloadCoordinator} (source-level: files, Vault
 * secrets) into the Spring {@code Environment} (property-level): every {@link SourceChangeEvent} updates
 * the matching {@link ReloadablePropertySource} in place and publishes a {@link
 * ConfigurationRefreshedEvent} so application code can react generically.
 */
public class EnvironmentPropertySourceBridge implements SourceChangeListener {

    private final ConfigurableEnvironment environment;
    private final ApplicationEventPublisher eventPublisher;

    public EnvironmentPropertySourceBridge(ConfigurableEnvironment environment, ApplicationEventPublisher eventPublisher) {
        this.environment = environment;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void onChange(SourceChangeEvent event) {
        String name = HotReloadPropertySourceNames.forSourceId(event.sourceId());
        PropertySource<?> existing = environment.getPropertySources().get(name);

        ReloadablePropertySource reloadable;
        if (existing instanceof ReloadablePropertySource r) {
            reloadable = r;
        } else {
            reloadable = new ReloadablePropertySource(name);
            environment.getPropertySources().addLast(reloadable);
        }
        reloadable.update(event.content());

        eventPublisher.publishEvent(new ConfigurationRefreshedEvent(this, event.sourceId(), event.changedKeys()));
    }
}
