package ru.ludwigandreas.grpc.server.interceptor;

import io.grpc.Context;
import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ludwigandreas.grpc.core.context.GrpcCallContext;

/**
 * One log line per call on {@code ludwig.grpcserver.<service>} - method, status, duration and
 * correlation id, never a message payload - and the {@code ludwig.grpcserver.calls.nodeadline}
 * counter.
 *
 * <p>A caller that sends no deadline can hold a handler thread for as long as the handler takes,
 * which is forever when the handler waits on something that hangs. The counter is how the owner of
 * the service finds out which callers do that.
 */
public final class AccessLogInterceptor implements BuiltInServerInterceptor {

    /**
     * The no-deadline counter's name.
     */
    public static final String NO_DEADLINE = "ludwig.grpcserver.calls.nodeadline";

    private static final String LOGGER_PREFIX = "ludwig.grpcserver.";

    private final MeterRegistry meters;
    private final Map<String, Logger> loggers = new ConcurrentHashMap<>();

    /**
     * Creates the interceptor.
     *
     * @param meters the meter registry, or {@code null} for no counter
     */
    public AccessLogInterceptor(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public Stage stage() {
        return Stage.ACCESS_LOG;
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                       ServerCallHandler<Q, R> next) {
        String service = call.getMethodDescriptor().getServiceName();
        String method = call.getMethodDescriptor().getBareMethodName();
        if (meters != null && Context.current().getDeadline() == null) {
            Counter.builder(NO_DEADLINE).tag("service", String.valueOf(service)).tag("method", String.valueOf(method))
                    .register(meters).increment();
        }
        Logger log = loggers.computeIfAbsent(String.valueOf(service),
                name -> LoggerFactory.getLogger(LOGGER_PREFIX + name));
        String correlationId = GrpcCallContext.CORRELATION_ID.get();
        long started = System.nanoTime();
        return next.startCall(new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                log.info("{} {} {}ms correlationId={}", method, status.getCode(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), correlationId);
                super.close(status, trailers);
            }
        }, headers);
    }
}
