package ru.ludwigandreas.archrules.fixture.bad.logging.catalog.logs;

/** Not repository access: the rest of {@code Runtime} is ordinary and must not be reported. */
public class WorkerPoolSizer {

    public int workers() {
        return Math.max(2, Runtime.getRuntime().availableProcessors());
    }
}
