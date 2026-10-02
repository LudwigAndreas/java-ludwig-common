package ru.ludwigandreas.fileaction.engine;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import ru.ludwigandreas.fileaction.exception.UnknownActionException;

/**
 * The resolved actions, looked up by name.
 *
 * <p>Immutable and built once, by {@code FileActionConfigurationValidator}. There is no registration method: an
 * action that could be added after startup would be one the validator never saw, which is the whole value of
 * the validator.
 */
public class FileActionRegistry {

    private final Map<String, ResolvedAction<?>> byName;

    /**
     * Holds the resolved actions.
     *
     * @param actions the actions, in the order the deployment declared them
     */
    public FileActionRegistry(Collection<ResolvedAction<?>> actions) {
        Map<String, ResolvedAction<?>> resolved = new LinkedHashMap<>();
        for (ResolvedAction<?> action : actions) {
            resolved.put(action.name(), action);
        }
        this.byName = Map.copyOf(resolved);
    }

    /**
     * The action a path names.
     *
     * @param name the action name
     * @return the action
     * @throws UnknownActionException if nothing is configured under that name
     */
    public ResolvedAction<?> require(String name) {
        return find(name).orElseThrow(() -> new UnknownActionException(name));
    }

    /**
     * The action a name refers to, if there is one.
     *
     * @param name the action name
     * @return the action, or empty
     */
    public Optional<ResolvedAction<?>> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** Every resolved action, in declaration order. */
    public Collection<ResolvedAction<?>> all() {
        return byName.values();
    }

    /** How many actions are configured. */
    public int size() {
        return byName.size();
    }
}
