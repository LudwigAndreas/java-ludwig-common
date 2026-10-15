package ru.ludwigandreas.grpc.core.context;

import io.grpc.Context;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/**
 * Runs the rest of a server call with a {@link Context} attached and every {@link ThreadBinding}
 * in force - on every callback, on whichever thread delivers it.
 *
 * <h2>The defect this class exists to prevent</h2>
 *
 * <p>The obvious implementation sets the thread-locals once, in {@code interceptCall}, and clears
 * them when it returns. It is wrong, and it passes every test written on a single-threaded
 * executor. {@code interceptCall} returns <em>before the request message has arrived</em>; for a
 * unary call the handler method runs later, inside {@code onHalfClose}, on a thread the executor
 * picks then. In production that is routinely a different thread, on which the security context is
 * empty, the MDC has no correlation id and the caller's locale is the JVM's. Worse, the thread
 * {@code interceptCall} ran on goes back to the pool - and if it was not cleaned up, the next
 * caller it serves inherits the previous caller's identity.
 *
 * <p>So the bindings are applied around {@code startCall} and around each of the five listener
 * callbacks, and undone in reverse order in a {@code finally} after each one. The cost is a few
 * thread-local writes per callback; the alternative is an authorization decision made with someone
 * else's principal.
 *
 * <p>Static helpers only: the interceptor that decides what goes <em>into</em> the context differs
 * per concern (authentication, correlation, preferences) and lives in the server starter. This is
 * the part they all share.
 */
public final class ContextRestoringServerInterceptor {

    private ContextRestoringServerInterceptor() {
    }

    /**
     * Continues a call inside {@code context} with {@code bindings} restored on every callback.
     *
     * @param context  the context to attach, carrying whatever the caller stored in it
     * @param bindings the thread bindings, applied in order and undone in reverse
     * @param call     the server call
     * @param headers  the request metadata
     * @param next     the rest of the chain
     * @param <Q>      the request type
     * @param <R>      the response type
     * @return the listener to hand back to gRPC
     */
    public static <Q, R> ServerCall.Listener<Q> interceptCall(
            Context context, List<ThreadBinding> bindings, ServerCall<Q, R> call, Metadata headers,
            ServerCallHandler<Q, R> next) {
        List<ThreadBinding> ordered = List.copyOf(bindings);
        ServerCall.Listener<Q> delegate = within(context, ordered, () -> next.startCall(call, headers));
        return new RestoringListener<>(delegate, context, ordered);
    }

    /**
     * Runs {@code action} with {@code context} attached and {@code bindings} in force.
     *
     * @param context  the context to attach
     * @param bindings the thread bindings
     * @param action   what to run
     * @param <T>      the result type
     * @return the action's result
     */
    public static <T> T within(Context context, List<ThreadBinding> bindings, Supplier<T> action) {
        Context previous = context.attach();
        Deque<ThreadBinding.Scope> scopes = new ArrayDeque<>(bindings.size());
        try {
            for (ThreadBinding binding : bindings) {
                scopes.push(binding.bind(context));
            }
            return action.get();
        } finally {
            // Reverse order, and each close attempted even if an earlier one threw: a scope left
            // open is a thread-local left set on a pooled thread, which is the leak this class is
            // for. The first failure is rethrown once everything has been unwound.
            RuntimeException failure = null;
            while (!scopes.isEmpty()) {
                try {
                    scopes.pop().close();
                } catch (RuntimeException closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    }
                }
            }
            context.detach(previous);
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class RestoringListener<Q>
            extends ForwardingServerCallListener.SimpleForwardingServerCallListener<Q> {

        private final Context context;
        private final List<ThreadBinding> bindings;

        RestoringListener(ServerCall.Listener<Q> delegate, Context context, List<ThreadBinding> bindings) {
            super(delegate);
            this.context = context;
            this.bindings = bindings;
        }

        @Override
        public void onMessage(Q message) {
            run(() -> super.onMessage(message));
        }

        @Override
        public void onHalfClose() {
            run(super::onHalfClose);
        }

        @Override
        public void onCancel() {
            run(super::onCancel);
        }

        @Override
        public void onComplete() {
            run(super::onComplete);
        }

        @Override
        public void onReady() {
            run(super::onReady);
        }

        private void run(Runnable callback) {
            within(context, bindings, () -> {
                callback.run();
                return null;
            });
        }
    }
}
