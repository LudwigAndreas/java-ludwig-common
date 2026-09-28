package ru.ludwigandreas.reconciliation.engine;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every configured task that has a bean, looked up by name.
 *
 * <p>Populated once at startup, after the configuration validator has already refused any context
 * where the beans and the configuration disagree - so by the time anything reads this registry, every
 * entry is known to have both halves. Lookups that miss therefore indicate a name that was never
 * configured at all, which is what an actuator call with a typo looks like.
 */
public class TaskRegistry {

    private final Map<String, RegisteredTask<?, ?, ?>> tasksByName;

    /**
     * Creates the registry.
     *
     * @param tasks the registered tasks, in configuration order
     */
    public TaskRegistry(Collection<RegisteredTask<?, ?, ?>> tasks) {
        Map<String, RegisteredTask<?, ?, ?>> byName = new LinkedHashMap<>();
        tasks.forEach(task -> byName.put(task.name(), task));
        this.tasksByName = Map.copyOf(byName);
    }

    /**
     * Looks a task up.
     *
     * @param name the task name
     * @return the task, or empty if no such task is configured
     */
    public Optional<RegisteredTask<?, ?, ?>> find(String name) {
        return Optional.ofNullable(tasksByName.get(name));
    }

    /** Every registered task. */
    public List<RegisteredTask<?, ?, ?>> all() {
        return List.copyOf(tasksByName.values());
    }

    /** Every registered task's name. */
    public List<String> names() {
        return List.copyOf(tasksByName.keySet());
    }
}
