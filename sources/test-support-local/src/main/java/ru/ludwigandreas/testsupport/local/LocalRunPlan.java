package ru.ludwigandreas.testsupport.local;

import java.util.Set;

/**
 * What a local application asked for, as the launched context sees it.
 *
 * <p>Registered as a singleton before the context refreshes, so the fixture runner can tell whether
 * Kafka was named and the summary can list what was started without either reaching for a static.
 * {@link LocalRun} creates it; it is public only so that {@link LocalRunConfiguration} can be started
 * in a test without the launcher.
 *
 * @param services the backing services the local application named
 */
public record LocalRunPlan(Set<BackingService> services) {

    /**
     * Creates the plan over a copy of the named services.
     *
     * @param services the backing services the local application named
     */
    public LocalRunPlan {
        services = Set.copyOf(services);
    }
}
