package ru.ludwigandreas.grpc.client.interceptor;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import ru.ludwigandreas.grpc.client.error.GrpcClientCallNotPermittedException;

/**
 * Rate limiter, bulkhead and circuit breaker, in that order, around the <em>logical</em> call.
 *
 * <p>One permit is taken when the call starts and one result recorded when it closes - after gRPC's
 * own retries, which run inside the channel. So the breaker counts calls, not attempts: three
 * failed attempts of one call are one failure, and a call that succeeds on its third attempt is one
 * success. Retry inside the breaker is the order {@code rest-client} uses, for the same reason: a
 * breaker that counted attempts would open three times faster during exactly the incidents where
 * the retries were absorbing the failure.
 *
 * <p>What the breaker counts is the dependency's failure and nothing else. A peer that answered
 * {@code INVALID_ARGUMENT}, {@code NOT_FOUND} or {@code PERMISSION_DENIED} is healthy and the
 * request was wrong; those release the permit unrecorded, as does an authentication failure.
 */
public final class ResilienceInterceptor implements ClientInterceptor {

    /** The codes that are the dependency's failure. */
    static final Set<Status.Code> COUNTED = EnumSet.of(Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED,
            Status.Code.INTERNAL, Status.Code.UNKNOWN, Status.Code.RESOURCE_EXHAUSTED, Status.Code.DATA_LOSS);

    private final String clientName;
    private final RateLimiter rateLimiter;
    private final Bulkhead bulkhead;
    private final CircuitBreaker circuitBreaker;
    private final Consumer<String> notPermitted;
    private final Supplier<String> correlationId;

    /**
     * Creates the interceptor. Any policy may be {@code null}.
     *
     * @param clientName     the client
     * @param rateLimiter    the rate limiter, or {@code null}
     * @param bulkhead       the bulkhead, or {@code null}
     * @param circuitBreaker the circuit breaker, or {@code null}
     * @param notPermitted   told the policy name each time a call is refused
     * @param correlationId  the current correlation id
     */
    public ResilienceInterceptor(String clientName, RateLimiter rateLimiter, Bulkhead bulkhead,
                                 CircuitBreaker circuitBreaker, Consumer<String> notPermitted,
                                 Supplier<String> correlationId) {
        this.clientName = clientName;
        this.rateLimiter = rateLimiter;
        this.bulkhead = bulkhead;
        this.circuitBreaker = circuitBreaker;
        this.notPermitted = notPermitted;
        this.correlationId = correlationId;
    }

    @Override
    public <Q, R> ClientCall<Q, R> interceptCall(MethodDescriptor<Q, R> method, CallOptions options, Channel next) {
        String refusedBy = acquire();
        if (refusedBy != null) {
            notPermitted.accept(refusedBy);
            return new RefusedCall<>(Status.UNAVAILABLE.withDescription("refused by the client's " + refusedBy)
                    .withCause(new GrpcClientCallNotPermittedException(clientName, correlationId.get(), refusedBy)));
        }
        long started = System.nanoTime();
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
            @Override
            public void start(Listener<R> listener, Metadata headers) {
                super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(listener) {
                    @Override
                    public void onClose(Status status, Metadata trailers) {
                        record(status, System.nanoTime() - started);
                        super.onClose(status, trailers);
                    }
                }, headers);
            }
        };
    }

    private String acquire() {
        if (rateLimiter != null && !rateLimiter.acquirePermission()) {
            return "rate-limiter";
        }
        if (bulkhead != null) {
            try {
                bulkhead.acquirePermission();
            } catch (BulkheadFullException full) {
                return "bulkhead";
            }
        }
        if (circuitBreaker != null && !circuitBreaker.tryAcquirePermission()) {
            if (bulkhead != null) {
                bulkhead.onComplete();
            }
            return "circuit-breaker";
        }
        return null;
    }

    private void record(Status status, long nanos) {
        if (bulkhead != null) {
            bulkhead.onComplete();
        }
        if (circuitBreaker == null) {
            return;
        }
        if (status.isOk()) {
            circuitBreaker.onSuccess(nanos, TimeUnit.NANOSECONDS);
        } else if (COUNTED.contains(status.getCode())) {
            circuitBreaker.onError(nanos, TimeUnit.NANOSECONDS, status.asRuntimeException());
        } else {
            circuitBreaker.releasePermission();
        }
    }
}
