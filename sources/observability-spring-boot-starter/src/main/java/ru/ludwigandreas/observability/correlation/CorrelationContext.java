package ru.ludwigandreas.observability.correlation;

import java.util.Optional;
import org.slf4j.MDC;

/**
 * The current unit of work's correlation id, and the only supported way to read or set it.
 *
 * <h2>Why the MDC is the storage, not a mirror of it</h2>
 *
 * <p>The obvious implementation keeps a {@code ThreadLocal} and copies it into the MDC so logs can
 * see it. That design has one failure mode and it is a bad one: the two can disagree, and when they
 * do, the id an operator reads in the logs is not the id the service propagated downstream - so the
 * search that is supposed to join the two halves of a request returns one of them. Keeping a single
 * copy in the MDC makes that disagreement unrepresentable.
 *
 * <p>It also means the id travels wherever the MDC already travels: Logback writes it without being
 * told to, and anything that already propagates the MDC across a thread boundary (a
 * {@code TaskDecorator}, Micrometer's context propagation) carries the correlation id for free
 * rather than needing a second mechanism taught about it.
 *
 * <p>The cost is the MDC's own limitation - it does not cross a thread boundary by itself - which is
 * why {@link #open(String)} returns a scope that restores the previous value rather than clearing
 * it. On a pooled thread, clearing would leak an <em>absence</em> into the next task to run there,
 * which looks like "this request had no correlation id" and is just as misleading as a wrong one.
 */
public class CorrelationContext {

    private final String mdcKey;

    public CorrelationContext(String mdcKey) {
        this.mdcKey = mdcKey;
    }

    /** MDC key - and therefore JSON log field - the id is published under. */
    public String mdcKey() {
        return mdcKey;
    }

    /** The current correlation id, or empty outside any correlated unit of work. */
    public Optional<String> currentId() {
        String value = MDC.get(mdcKey);
        return value == null || value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    /**
     * Binds {@code correlationId} for the current thread until the returned scope is closed.
     *
     * <p>Always use it with try-with-resources. A scope that is not closed outlives the work it
     * describes and attributes every later log line on that thread - a different request, on a
     * pooled thread - to the wrong unit of work.
     *
     * @param correlationId the id to bind; {@code null} binds nothing but still yields a closeable
     *                      scope, so callers need no branch
     */
    public Scope open(String correlationId) {
        String previous = MDC.get(mdcKey);
        if (correlationId != null && !correlationId.isEmpty()) {
            MDC.put(mdcKey, correlationId);
        }
        return () -> {
            if (previous == null) {
                MDC.remove(mdcKey);
            } else {
                MDC.put(mdcKey, previous);
            }
        };
    }

    /** An open binding; closing restores whatever was bound before. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {

        /** Narrowed from {@link AutoCloseable} so try-with-resources needs no catch block. */
        @Override
        void close();
    }
}
