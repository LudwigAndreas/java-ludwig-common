package ru.ludwigandreas.grpc.client.interceptor;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Deadline;
import io.grpc.MethodDescriptor;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Every call gets a deadline: the earliest of the client's configured one, one the call site set,
 * and - which gRPC applies itself from the current {@code Context} - the remaining deadline of the
 * inbound call this one is made within.
 *
 * <p>The deadline bounds the logical call, retries included. gRPC has no stable per-attempt timeout
 * and this does not invent one.
 */
public final class DeadlineInterceptor implements ClientInterceptor {

    private final Supplier<Duration> deadline;

    /**
     * Creates the interceptor.
     *
     * @param deadline the configured ceiling, read per call so a refresh takes effect
     */
    public DeadlineInterceptor(Supplier<Duration> deadline) {
        this.deadline = deadline;
    }

    @Override
    public <Q, R> ClientCall<Q, R> interceptCall(MethodDescriptor<Q, R> method, CallOptions options, Channel next) {
        Deadline ceiling = Deadline.after(deadline.get().toNanos(), TimeUnit.NANOSECONDS);
        Deadline requested = options.getDeadline();
        return next.newCall(method, options.withDeadline(requested == null ? ceiling : requested.minimum(ceiling)));
    }
}
