package ru.ludwigandreas.grpc.client.interceptor;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientStreamTracer;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.audit.redaction.Redactor;
import ru.ludwigandreas.grpc.client.audit.GrpcCallAudit;
import ru.ludwigandreas.grpc.client.error.GrpcClientCallNotPermittedException;

/**
 * Everything recorded about a logical call once it ends: the log line, the retry counter and the
 * audit record.
 *
 * <p>Attempts are counted by a stream tracer rather than by an interceptor, because gRPC's retries
 * happen inside the channel and an interceptor sees only the logical call. A tracer is created per
 * attempt, so the count is exact.
 *
 * <p>Payloads are never logged at the default level. At {@code payload} they are rendered as JSON
 * through audit-core's {@link Redactor}, capped, and the validator refuses that level under a
 * production profile.
 */
public final class CallRecordingInterceptor implements ClientInterceptor {

    /** The retry counter's name. */
    public static final String RETRIES = "ludwig.grpcclient.retries";

    private static final String LOGGER_PREFIX = "ludwig.grpcclient.";
    private static final JsonFormat.Printer JSON = JsonFormat.printer().omittingInsignificantWhitespace();

    private final String clientName;
    private final Supplier<Settings> settings;
    private final MeterRegistry meters;
    private final AuditSink audit;
    private final Redactor redactor;
    private final Supplier<String> correlationId;
    private final Supplier<String> principal;
    private final Clock clock;
    private final Logger log;

    /**
     * Creates the interceptor.
     *
     * @param clientName    the client
     * @param settings      the refreshable settings, read per call
     * @param meters        the meter registry, or {@code null}
     * @param audit         the platform's audit sink, or {@code null}
     * @param redactor      the platform's redactor, or {@code null}
     * @param correlationId the current correlation id
     * @param principal     the current caller's name, or {@code null}
     * @param clock         the clock
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public CallRecordingInterceptor(String clientName, Supplier<Settings> settings, MeterRegistry meters,
                                    AuditSink audit, Redactor redactor, Supplier<String> correlationId,
                                    Supplier<String> principal, Clock clock) {
        this.clientName = clientName;
        this.settings = settings;
        this.meters = meters;
        this.audit = audit;
        this.redactor = redactor;
        this.correlationId = correlationId;
        this.principal = principal;
        this.clock = clock;
        this.log = LoggerFactory.getLogger(LOGGER_PREFIX + clientName);
    }

    @Override
    public <Q, R> ClientCall<Q, R> interceptCall(MethodDescriptor<Q, R> method, CallOptions options, Channel next) {
        Settings current = settings.get();
        Instant at = clock.instant();
        long started = System.nanoTime();
        String correlation = correlationId.get();
        String caller = principal.get();
        AtomicInteger attempts = new AtomicInteger();
        CallOptions traced = options.withStreamTracerFactory(new ClientStreamTracer.Factory() {
            @Override
            public ClientStreamTracer newClientStreamTracer(ClientStreamTracer.StreamInfo info, Metadata headers) {
                attempts.incrementAndGet();
                if (info.getPreviousAttempts() > 0 && !info.isTransparentRetry() && meters != null) {
                    Counter.builder(RETRIES).tag("client", clientName).tag("method", method.getBareMethodName())
                            .register(meters).increment();
                }
                return new ClientStreamTracer() {
                };
            }
        });
        String fullName = method.getFullMethodName();
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, traced)) {
            @Override
            public void start(Listener<R> listener, Metadata headers) {
                super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(listener) {
                    @Override
                    public void onMessage(R message) {
                        payload(current, "received", message);
                        super.onMessage(message);
                    }

                    @Override
                    public void onClose(Status status, Metadata trailers) {
                        Duration took = Duration.ofNanos(System.nanoTime() - started);
                        if (!"none".equals(current.level())) {
                            log.info("{} {} {}ms attempts={} correlationId={}", method.getBareMethodName(),
                                    status.getCode(), took.toMillis(), attempts.get(), correlation);
                        }
                        audit(current, fullName, status, took, attempts.get(), correlation, caller, at);
                        super.onClose(status, trailers);
                    }
                }, headers);
            }

            @Override
            public void sendMessage(Q message) {
                payload(current, "sent", message);
                super.sendMessage(message);
            }
        };
    }

    private void payload(Settings current, String direction, Object message) {
        if (!"payload".equals(current.level()) || redactor == null || !(message instanceof MessageOrBuilder proto)) {
            return;
        }
        try {
            log.info("{} {}", direction, redactor.redactBody(LOGGER_PREFIX + clientName, JSON.print(proto),
                    "application/json", current.maxPayloadChars()));
        } catch (InvalidProtocolBufferException unprintable) {
            log.debug("{} a message that could not be rendered", direction);
        }
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private void audit(Settings current, String method, Status status, Duration took, int attempts,
                       String correlation, String caller, Instant at) {
        if (audit == null || !current.auditEnabled()) {
            return;
        }
        Throwable cause = status.getCause();
        String outcome = status.isOk() ? GrpcCallAudit.SUCCESS
                : cause instanceof GrpcClientCallNotPermittedException ? GrpcCallAudit.NOT_PERMITTED
                : GrpcCallAudit.FAILURE;
        boolean sampledOut = status.isOk() && (current.successSampling() <= 0.0
                || current.successSampling() < 1.0
                && ThreadLocalRandom.current().nextDouble() >= current.successSampling());
        if (sampledOut) {
            return;
        }
        audit.record(new GrpcCallAudit(clientName, method, status.getCode().name(), outcome, took, attempts,
                correlation, caller, at, cause == null ? null : cause.getClass().getSimpleName()).toAuditEvent());
    }

    /**
     * The settings a refresh may change.
     *
     * @param level           {@code none}, {@code basic} or {@code payload}
     * @param maxPayloadChars the cap on a logged payload
     * @param auditEnabled    whether calls are audited
     * @param successSampling the share of successful calls audited
     */
    public record Settings(String level, int maxPayloadChars, boolean auditEnabled, double successSampling) {
    }
}
