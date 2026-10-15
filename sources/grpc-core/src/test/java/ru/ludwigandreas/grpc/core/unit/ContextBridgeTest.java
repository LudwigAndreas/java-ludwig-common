package ru.ludwigandreas.grpc.core.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.grpc.core.context.ContextRestoringServerInterceptor;
import ru.ludwigandreas.grpc.core.context.GrpcCallContext;
import ru.ludwigandreas.grpc.core.context.ThreadBinding;

/**
 * Exercises the bridge the way gRPC does: the interceptor runs on one thread and each callback is
 * delivered on another. A single-threaded version of this test passes against the broken
 * set-once-in-interceptCall implementation, which is exactly why it is not written that way.
 */
class ContextBridgeTest {

    /** Stands in for a subsystem's thread-local: the security context, the MDC, the preferences. */
    private static final ThreadLocal<String> CALLER = new ThreadLocal<>();

    private static final ThreadBinding CALLER_BINDING = context -> {
        String previous = CALLER.get();
        CALLER.set(GrpcCallContext.CORRELATION_ID.get(context));
        return () -> {
            if (previous == null) {
                CALLER.remove();
            } else {
                CALLER.set(previous);
            }
        };
    };

    private final ExecutorService interceptorThread = Executors.newSingleThreadExecutor();
    private final ExecutorService callbackThread = Executors.newSingleThreadExecutor();

    @AfterEach
    void shutDown() {
        interceptorThread.shutdownNow();
        callbackThread.shutdownNow();
    }

    @Test
    @DisplayName("a callback on a different thread sees the caller, and the thread is clean afterwards")
    void restoresOnTheCallbackThread() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        ServerCall.Listener<String> listenerA = intercept("caller-a", handler);
        ServerCall.Listener<String> listenerB = intercept("caller-b", handler);

        // The interceptor's own thread must be clean once interceptCall has returned.
        assertThat(interceptorThread.submit(CALLER::get).get(5, TimeUnit.SECONDS)).isNull();

        // Interleaved on ONE pooled thread, as an executor would: A, then B, then A again.
        callbackThread.submit(listenerA::onHalfClose).get(5, TimeUnit.SECONDS);
        callbackThread.submit(listenerB::onHalfClose).get(5, TimeUnit.SECONDS);
        callbackThread.submit(() -> listenerA.onMessage("more")).get(5, TimeUnit.SECONDS);
        callbackThread.submit(listenerB::onCancel).get(5, TimeUnit.SECONDS);
        callbackThread.submit(listenerA::onComplete).get(5, TimeUnit.SECONDS);
        callbackThread.submit(listenerA::onReady).get(5, TimeUnit.SECONDS);

        assertThat(handler.seen).containsExactly(
                "startCall:caller-a", "startCall:caller-b",
                "onHalfClose:caller-a", "onHalfClose:caller-b", "onMessage:caller-a",
                "onCancel:caller-b", "onComplete:caller-a", "onReady:caller-a");
        // Nothing of either caller is left on the pooled thread for whoever it serves next.
        assertThat(callbackThread.submit(CALLER::get).get(5, TimeUnit.SECONDS)).isNull();
        assertThat(callbackThread.submit(() -> GrpcCallContext.CORRELATION_ID.get()).get(5, TimeUnit.SECONDS))
                .isNull();
    }

    @Test
    @DisplayName("a callback that throws still leaves the thread clean")
    void unwindsWhenTheCallbackThrows() throws Exception {
        ServerCall.Listener<String> listener = intercept("caller-a", new RecordingHandler() {
            @Override
            public ServerCall.Listener<String> startCall(ServerCall<String, String> call, Metadata headers) {
                return new ServerCall.Listener<>() {
                    @Override
                    public void onHalfClose() {
                        throw new IllegalStateException("handler failed");
                    }
                };
            }
        });

        assertThatThrownBy(() -> callbackThread.submit(listener::onHalfClose).get(5, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(callbackThread.submit(CALLER::get).get(5, TimeUnit.SECONDS)).isNull();
    }

    @Test
    @DisplayName("scopes close in reverse order, and an outer value on the same thread is put back")
    void closesInReverseAndRestoresThePreviousValue() {
        List<String> order = new ArrayList<>();
        ThreadBinding first = context -> {
            order.add("open-1");
            return () -> order.add("close-1");
        };
        ThreadBinding second = context -> {
            order.add("open-2");
            return () -> order.add("close-2");
        };
        CALLER.set("outer");
        try {
            Context inner = Context.current().withValue(GrpcCallContext.CORRELATION_ID, "inner");

            String during = ContextRestoringServerInterceptor.within(
                    inner, List.of(first, CALLER_BINDING, second), CALLER::get);

            assertThat(during).isEqualTo("inner");
            assertThat(CALLER.get()).isEqualTo("outer");
            assertThat(order).containsExactly("open-1", "open-2", "close-2", "close-1");
        } finally {
            CALLER.remove();
        }
    }

    @Test
    @DisplayName("every scope is closed even when one of them fails to close")
    void closesEveryScopeWhenOneFails() {
        List<String> closed = new ArrayList<>();
        ThreadBinding healthy = context -> () -> closed.add("healthy");
        ThreadBinding failing = context -> () -> {
            throw new IllegalStateException("close failed");
        };

        assertThatThrownBy(() -> ContextRestoringServerInterceptor.within(
                Context.current(), List.of(healthy, failing), () -> "done"))
                .isInstanceOf(IllegalStateException.class).hasMessage("close failed");
        assertThat(closed).containsExactly("healthy");
    }

    private ServerCall.Listener<String> intercept(String caller, ServerCallHandler<String, String> handler)
            throws Exception {
        return interceptorThread.submit(() -> ContextRestoringServerInterceptor.interceptCall(
                Context.ROOT.withValue(GrpcCallContext.CORRELATION_ID, caller),
                List.of(CALLER_BINDING), null, new Metadata(), handler)).get(5, TimeUnit.SECONDS);
    }

    private static class RecordingHandler implements ServerCallHandler<String, String> {

        final List<String> seen = new ArrayList<>();

        @Override
        public ServerCall.Listener<String> startCall(ServerCall<String, String> call, Metadata headers) {
            seen.add("startCall:" + CALLER.get());
            return new ServerCall.Listener<>() {
                @Override
                public void onMessage(String message) {
                    seen.add("onMessage:" + CALLER.get());
                }

                @Override
                public void onHalfClose() {
                    seen.add("onHalfClose:" + CALLER.get());
                }

                @Override
                public void onCancel() {
                    seen.add("onCancel:" + CALLER.get());
                }

                @Override
                public void onComplete() {
                    seen.add("onComplete:" + CALLER.get());
                }

                @Override
                public void onReady() {
                    seen.add("onReady:" + CALLER.get());
                }
            };
        }
    }
}
