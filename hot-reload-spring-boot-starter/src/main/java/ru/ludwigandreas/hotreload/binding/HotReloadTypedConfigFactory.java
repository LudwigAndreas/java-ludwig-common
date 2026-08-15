package ru.ludwigandreas.hotreload.binding;

import org.springframework.core.env.ConfigurableEnvironment;
import ru.ludwigandreas.hotreload.core.SourceReloadCoordinator;

import java.util.Set;

/**
 * Creates {@link RefreshableConfig} instances and wires each one to refresh automatically whenever a
 * hot-reloaded source touches a key under its configuration prefix - the one-stop entry point for
 * "typed configuration" that stays current without an application restart.
 *
 * <pre>{@code
 * RefreshableConfig<MyServiceProperties> config = typedConfigFactory.create("myapp.myservice", MyServiceProperties.class);
 * MyServiceProperties current = config.get();
 * }</pre>
 */
public class HotReloadTypedConfigFactory {

    private final ConfigurableEnvironment environment;
    private final ConfigurationBinder binder;
    private final SourceReloadCoordinator coordinator;

    public HotReloadTypedConfigFactory(ConfigurableEnvironment environment, ConfigurationBinder binder,
                                        SourceReloadCoordinator coordinator) {
        this.environment = environment;
        this.binder = binder;
        this.coordinator = coordinator;
    }

    public <T> RefreshableConfig<T> create(String prefix, Class<T> type) {
        RefreshableConfig<T> config = new RefreshableConfig<>(environment, prefix, type, binder);
        coordinator.addListener(event -> {
            if (touchesPrefix(prefix, event.changedKeys())) {
                config.refresh(environment);
            }
        });
        return config;
    }

    private static boolean touchesPrefix(String prefix, Set<String> changedKeys) {
        String nested = prefix + ".";
        for (String key : changedKeys) {
            if (key.equals(prefix) || key.startsWith(nested)) {
                return true;
            }
        }
        return false;
    }
}
