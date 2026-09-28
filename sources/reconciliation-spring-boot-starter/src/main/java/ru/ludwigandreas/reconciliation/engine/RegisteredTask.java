package ru.ludwigandreas.reconciliation.engine;

import ru.ludwigandreas.reconciliation.api.SyncTask;
import ru.ludwigandreas.reconciliation.config.TaskSettings;

/**
 * A discovered {@link SyncTask} bean paired with its resolved configuration.
 *
 * <p>Generic in all three of the task's type parameters, and held in the registry as
 * {@code RegisteredTask<?, ?, ?>}. That pairing is what keeps the unavoidable wildcard capture in one
 * place: the engine's generic methods take a {@code RegisteredTask<I, K, O>}, Java captures the
 * wildcards at the call site, and every line inside those methods is fully typed. The alternative -
 * raw types and casts scattered through the walkers - is how a fetcher's records end up handed to the
 * wrong task's reconciler with nothing but a {@code ClassCastException} at the far end to say so.
 *
 * @param <I> the local record type
 * @param <K> the correlation key type
 * @param <O> the external record type
 */
public final class RegisteredTask<I, K, O> {

    private final SyncTask<I, K, O> task;
    private final TaskSettings settings;

    /**
     * Pairs a task with its settings.
     *
     * @param task     the task bean
     * @param settings its resolved configuration
     */
    public RegisteredTask(SyncTask<I, K, O> task, TaskSettings settings) {
        this.task = task;
        this.settings = settings;
    }

    /** The task's name. */
    public String name() {
        return settings.name();
    }

    /** The task bean. */
    public SyncTask<I, K, O> task() {
        return task;
    }

    /** The task's resolved configuration. */
    public TaskSettings settings() {
        return settings;
    }

    /**
     * The correlation key of a local record, rendered for the {@code correlation_key} column.
     *
     * @param local the local record
     * @return the encoded key
     */
    public String encodedKeyOf(I local) {
        return task.keyCodec().encode(task.localKey().apply(local));
    }

    @Override
    public String toString() {
        return "RegisteredTask(" + name() + ", " + settings.fetch().shape() + ")";
    }
}
