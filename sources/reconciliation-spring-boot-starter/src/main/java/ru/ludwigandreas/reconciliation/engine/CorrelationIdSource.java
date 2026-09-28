package ru.ludwigandreas.reconciliation.engine;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Where a run's correlation id comes from, and how it is bound for the duration of the run.
 *
 * <p>An indirection rather than a direct call into the observability starter, because that starter is
 * an optional dependency here: a service can use this module without it, and a hard reference would
 * turn "we do not ship JSON logging" into a {@code NoClassDefFoundError} at the first tick. The
 * autoconfiguration installs the observability-backed implementation when the starter is present and
 * a self-contained one when it is not.
 */
public interface CorrelationIdSource {

    /**
     * Binds a correlation id for the current thread and returns it, along with the scope that unbinds
     * it.
     *
     * <p>Always used with try-with-resources. A scope left open outlives the run it describes and
     * attributes every later log line on that pooled thread to the wrong unit of work.
     *
     * @return the open scope
     */
    Scope open();

    /** An open correlation scope. */
    interface Scope extends AutoCloseable {

        /** The correlation id bound for this scope. */
        String correlationId();

        /** Unbinds it, restoring whatever was bound before. */
        @Override
        void close();
    }

    /**
     * The fallback used when the observability starter is absent: a fresh id per run, bound nowhere.
     *
     * <p>The id is still stamped onto staged rows and audit events, so the trail within this module
     * stays joinable. What is lost without the starter is propagation into the outbound calls and into
     * the log lines, which is exactly what that starter is for.
     */
    static CorrelationIdSource standalone() {
        return () -> new Scope() {
            private final String id = UUID.randomUUID().toString();

            @Override
            public String correlationId() {
                return id;
            }

            @Override
            public void close() {
                // Nothing was bound, so there is nothing to restore.
            }
        };
    }

    /**
     * Adapts an arbitrary id supplier and binding function - what the autoconfiguration uses to wire
     * the observability starter's {@code CorrelationContext} without naming its type here.
     *
     * @param ids      supplies a fresh id
     * @param binder   binds an id and returns how to unbind it
     * @return the source
     */
    static CorrelationIdSource of(Supplier<String> ids, java.util.function.Function<String, Runnable> binder) {
        return () -> {
            String id = ids.get();
            Runnable unbind = binder.apply(id);
            return new Scope() {
                @Override
                public String correlationId() {
                    return id;
                }

                @Override
                public void close() {
                    unbind.run();
                }
            };
        };
    }
}
