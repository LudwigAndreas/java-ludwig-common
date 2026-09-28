package ru.ludwigandreas.ingest.engine;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every configured task that has a bean, looked up by name.
 *
 * <p>Populated once at startup, after the validator has already refused any context where the beans
 * and the configuration disagree - so a lookup that misses indicates a name that was never configured
 * at all, which is what an actuator call with a typo looks like.
 */
public class IngestTaskRegistry {

    private final Map<String, RegisteredIngestTask> byName;

    /**
     * Creates the registry.
     *
     * @param tasks the registered tasks, in configuration order
     */
    public IngestTaskRegistry(Collection<RegisteredIngestTask> tasks) {
        Map<String, RegisteredIngestTask> map = new LinkedHashMap<>();
        tasks.forEach(task -> map.put(task.name(), task));
        this.byName = Map.copyOf(map);
    }

    /**
     * Looks a task up.
     *
     * @param name the task name
     * @return the task, or empty if no such task is configured
     */
    public Optional<RegisteredIngestTask> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /**
     * Every registered task.
     *
     * @return the tasks, in configuration order
     */
    public List<RegisteredIngestTask> all() {
        return List.copyOf(byName.values());
    }

    /**
     * Every registered task's name.
     *
     * @return the names, in configuration order
     */
    public List<String> names() {
        return List.copyOf(byName.keySet());
    }
}
