package ru.ludwigandreas.grpc.server.interceptor;

import io.grpc.Context;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import ru.ludwigandreas.grpc.core.context.ContextRestoringServerInterceptor;
import ru.ludwigandreas.grpc.core.context.ThreadBinding;

/**
 * Closes a failed call with the status the platform's problem mapping yields.
 *
 * <p>A failure reaches a caller by one of two roads and both are covered. A handler (or an inner
 * interceptor) <em>throws</em>: the exception surfaces from {@code startCall} or a listener
 * callback and is caught here. A handler calls {@code responseObserver.onError(e)}: gRPC turns that
 * into {@code close(UNKNOWN.withCause(e))}, and the cause never crosses the wire - so
 * {@code close} is intercepted and the cause is translated there.
 */
public final class ExceptionTranslatingInterceptor implements BuiltInServerInterceptor {

    private final GrpcProblemRenderer renderer;
    private final CallContextContributors contributors;

    /**
     * Creates the interceptor.
     *
     * @param renderer     renders a failure as a status
     * @param contributors the bindings a failure is rendered inside
     */
    public ExceptionTranslatingInterceptor(GrpcProblemRenderer renderer, CallContextContributors contributors) {
        this.renderer = renderer;
        this.contributors = contributors;
    }

    @Override
    public Stage stage() {
        return Stage.EXCEPTION;
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                       ServerCallHandler<Q, R> next) {
        CallState state = new CallState();
        TranslatingCall<Q, R> translating =
                new TranslatingCall<>(call, Context.current().withValue(CallState.KEY, state), state);
        ServerCall.Listener<Q> delegate = translating.guard(() -> next.startCall(translating, headers));
        if (delegate == null) {
            return new ServerCall.Listener<>() {
            };
        }
        return new TranslatingListener<>(delegate, translating);
    }

    private final class TranslatingCall<Q, R> extends ForwardingServerCall.SimpleForwardingServerCall<Q, R> {

        private final Context context;
        private final CallState state;
        private final AtomicBoolean closed = new AtomicBoolean();

        TranslatingCall(ServerCall<Q, R> delegate, Context context, CallState state) {
            super(delegate);
            this.context = context;
            this.state = state;
        }

        <T> T guard(Supplier<T> action) {
            Context previous = context.attach();
            // CHECKSTYLE.OFF: IllegalCatch - translating whatever a handler throws is this class's job
            try {
                return action.get();
            } catch (RuntimeException failure) {
                if (closed.compareAndSet(false, true)) {
                    closeWith(failure);
                }
                return null;
            } finally {
                context.detach(previous);
            }
            // CHECKSTYLE.ON: IllegalCatch
        }

        @Override
        public void close(Status status, Metadata trailers) {
            if (!closed.compareAndSet(false, true)) {
                // A handler that threw after answering: the call already has its status.
                return;
            }
            Throwable cause = status.getCause();
            boolean untranslated = status.getCode() == Status.Code.UNKNOWN && cause != null
                    && !(cause instanceof StatusRuntimeException) && !(cause instanceof StatusException);
            if (untranslated) {
                closeWith(cause);
            } else {
                super.close(status, trailers);
            }
        }

        private void closeWith(Throwable failure) {
            Context bridged = state.bridged();
            List<ThreadBinding> bindings = bridged == null ? List.of() : contributors.bindings();
            String method = getMethodDescriptor().getFullMethodName();
            StatusRuntimeException rendered = ContextRestoringServerInterceptor.within(
                    bridged == null ? context : bridged, bindings, () -> renderer.render(failure, method));
            Metadata trailers = rendered.getTrailers();
            super.close(rendered.getStatus(), trailers == null ? new Metadata() : trailers);
        }
    }

    private static final class TranslatingListener<Q>
            extends ForwardingServerCallListener.SimpleForwardingServerCallListener<Q> {

        private final TranslatingCall<Q, ?> call;

        TranslatingListener(ServerCall.Listener<Q> delegate, TranslatingCall<Q, ?> call) {
            super(delegate);
            this.call = call;
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
            call.guard(() -> {
                callback.run();
                return null;
            });
        }
    }
}
