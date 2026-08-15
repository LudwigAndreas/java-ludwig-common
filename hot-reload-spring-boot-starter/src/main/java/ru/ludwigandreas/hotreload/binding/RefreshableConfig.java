package ru.ludwigandreas.hotreload.binding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A live-updating, validated, typed view over a configuration namespace - the "typed configuration"
 * counterpart to the raw {@link ru.ludwigandreas.hotreload.propertysource.ReloadablePropertySource}.
 * Inject/hold one of these (via {@code HotReloadAutoConfiguration#refreshableConfig} or by constructing
 * it directly) instead of a plain {@code @ConfigurationProperties} bean when a type needs to pick up
 * changes without an application restart: call {@link #get()} for the current value, or {@link
 * #addListener} to be notified of every successful refresh.
 *
 * <p>{@link #refresh(Environment)} keeps serving the last valid value if rebinding or validation fails -
 * a malformed edit to a properties file or an invalid Vault secret value never replaces a working
 * configuration with a broken one.
 */
public class RefreshableConfig<T> {

    private static final Logger log = LoggerFactory.getLogger(RefreshableConfig.class);

    private final String prefix;
    private final Class<T> type;
    private final ConfigurationBinder binder;
    private final CopyOnWriteArrayList<Consumer<T>> listeners = new CopyOnWriteArrayList<>();

    private volatile T current;

    public RefreshableConfig(Environment environment, String prefix, Class<T> type, ConfigurationBinder binder) {
        this.prefix = prefix;
        this.type = type;
        this.binder = binder;
        this.current = binder.bindAndValidate(environment, prefix, type);
    }

    public T get() {
        return current;
    }

    public void addListener(Consumer<T> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<T> listener) {
        listeners.remove(listener);
    }

    /** @return {@code true} if the rebind succeeded and {@link #get()} now reflects the new value */
    public boolean refresh(Environment environment) {
        T rebound;
        try {
            rebound = binder.bindAndValidate(environment, prefix, type);
        } catch (Exception e) {
            log.warn("Refresh of configuration '{}' ({}) failed validation/binding, keeping previous value",
                    prefix, type.getSimpleName(), e);
            return false;
        }
        current = rebound;
        for (Consumer<T> listener : listeners) {
            try {
                listener.accept(rebound);
            } catch (Exception e) {
                log.error("Listener for configuration '{}' threw while handling a refresh", prefix, e);
            }
        }
        return true;
    }
}
