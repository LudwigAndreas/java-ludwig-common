package ru.ludwigandreas.reconciliation.engine.enumeration;

import ru.ludwigandreas.reconciliation.api.EnumerationTask;
import ru.ludwigandreas.reconciliation.config.EnumerationSettings;

/**
 * An enumeration bean paired with its resolved settings.
 *
 * <p>Carries the four type parameters so that the engine's generic methods can capture them once and
 * stay fully typed from the scope source through to the reconciler.
 *
 * @param <S>  the local scope type
 * @param <SK> the scope key type
 * @param <M>  the item type
 * @param <MK> the member key type
 */
public final class RegisteredEnumeration<S, SK, M, MK> {

    private final EnumerationTask<S, SK, M, MK> task;
    private final EnumerationSettings settings;

    /**
     * Pairs a bean with its settings.
     *
     * @param task     the bean
     * @param settings its resolved settings
     */
    public RegisteredEnumeration(EnumerationTask<S, SK, M, MK> task, EnumerationSettings settings) {
        this.task = task;
        this.settings = settings;
    }

    /** The enumeration's name. */
    public String name() {
        return settings.name();
    }

    /** The bean. */
    public EnumerationTask<S, SK, M, MK> task() {
        return task;
    }

    /** The resolved settings. */
    public EnumerationSettings settings() {
        return settings;
    }

    @Override
    public String toString() {
        return "RegisteredEnumeration(" + name() + ")";
    }
}
