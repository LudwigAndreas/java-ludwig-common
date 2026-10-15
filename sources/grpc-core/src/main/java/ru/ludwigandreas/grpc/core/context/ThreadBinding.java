package ru.ludwigandreas.grpc.core.context;

import io.grpc.Context;

/**
 * Establishes one piece of thread-bound state for the duration of one callback.
 *
 * <p>An implementation reads what it needs from the call's {@link Context} - which is already
 * attached to the current thread when {@link #bind} runs - writes it into whatever thread-local its
 * subsystem uses, and returns a {@link Scope} that puts the thread back exactly as it was found.
 * "As it was found" rather than "cleared": executor threads are shared, and a scope that clears
 * unconditionally erases state an outer scope on the same thread still needs.
 *
 * <p>Three bindings exist on a server - the security context, the correlation MDC entry and the
 * caller preferences - and each is contributed by the starter that owns the subsystem, which is how
 * this module restores all three while depending on none of them.
 */
@FunctionalInterface
public interface ThreadBinding {

    /**
     * Binds this subsystem's state to the current thread.
     *
     * @param context the call's context, attached to the current thread
     * @return the scope that undoes the binding; never {@code null}
     */
    Scope bind(Context context);

    /**
     * Undoes one {@link ThreadBinding}.
     */
    @FunctionalInterface
    interface Scope extends AutoCloseable {

        /**
         * A scope for a binding that had nothing to bind.
         */
        Scope NOOP = () -> {
        };

        @Override
        void close();
    }
}
