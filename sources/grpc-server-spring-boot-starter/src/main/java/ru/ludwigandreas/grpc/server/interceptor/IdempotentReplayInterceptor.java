package ru.ludwigandreas.grpc.server.interceptor;

import io.grpc.BindableService;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerMethodDefinition;
import io.grpc.Status;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.StringUtils;
import ru.ludwigandreas.grpc.core.metadata.GrpcMetadataKeys;
import ru.ludwigandreas.grpc.server.LudwigGrpcService;
import ru.ludwigandreas.grpc.server.error.GrpcServerConfigurationException;
import ru.ludwigandreas.grpc.server.error.GrpcServerMessages;
import ru.ludwigandreas.idempotency.api.ClaimMode;
import ru.ludwigandreas.idempotency.api.ClaimOutcome;
import ru.ludwigandreas.idempotency.api.ClaimRequest;
import ru.ludwigandreas.idempotency.api.ClaimResult;
import ru.ludwigandreas.idempotency.api.Idempotent;
import ru.ludwigandreas.idempotency.api.IdempotencyStore;
import ru.ludwigandreas.idempotency.api.RequestFingerprint;
import ru.ludwigandreas.idempotency.api.StoredResponse;
import ru.ludwigandreas.idempotency.config.IdempotencyProperties;
import ru.ludwigandreas.idempotency.error.ClaimInProgressException;
import ru.ludwigandreas.idempotency.error.ClaimNotReplayableException;
import ru.ludwigandreas.idempotency.error.FingerprintMismatchException;
import ru.ludwigandreas.idempotency.error.IdempotencyKeyRequiredException;
import ru.ludwigandreas.idempotency.error.IdempotencyKeyTooLongException;
import ru.ludwigandreas.idempotency.metrics.IdempotencyMetrics;

/**
 * At-most-once execution of a unary method that carries {@link Idempotent}, through the idempotency
 * module's own store.
 *
 * <p>The gRPC counterpart of {@code IdempotencyFilter}, and deliberately nothing more: the same
 * {@link IdempotencyStore}, the same {@link ClaimMode#STANDALONE} claim, the same problems for a
 * key in progress and a key reused with a different request. What differs is only where the pieces
 * come from - the key from metadata, the scope from the method's full name, the fingerprint from
 * the serialized request message, the stored response from the serialized reply.
 *
 * <p>{@code @Idempotent} is read reflectively from the service class once every singleton exists.
 * gRPC dispatches to the object {@code bindService()} captured rather than through a Spring proxy,
 * so an aspect on the annotation would never run; reading it here is what makes it work.
 *
 * <p>A response is stored only when the call closes {@code OK}. A handler that fails, or a caller
 * that cancels, releases the key, so the retry runs the handler again.
 */
public final class IdempotentReplayInterceptor implements BuiltInServerInterceptor, SmartInitializingSingleton {

    /**
     * The content type a stored gRPC reply is recorded under.
     */
    public static final String CONTENT_TYPE = "application/grpc+proto";

    private static final int MAX_KEY_LENGTH = 255;
    private static final int STORED_STATUS = 200;

    private final ListableBeanFactory beans;
    private final ObjectProvider<IdempotencyStore> stores;
    private final ObjectProvider<RequestFingerprint> fingerprints;
    private final ObjectProvider<IdempotencyProperties> properties;
    private final ObjectProvider<IdempotencyMetrics> metrics;
    private final GrpcProblemRenderer renderer;
    private final Map<String, Policy> policies = new LinkedHashMap<>();

    /**
     * Creates the interceptor.
     *
     * @param beans        where the stereotyped services are found
     * @param stores       the idempotency store, when one is configured
     * @param fingerprints the idempotency module's fingerprint
     * @param properties   the idempotency module's configuration
     * @param metrics      the idempotency module's metrics
     * @param renderer     renders a claim in progress with its retry-after
     */
    public IdempotentReplayInterceptor(ListableBeanFactory beans, ObjectProvider<IdempotencyStore> stores,
                                       ObjectProvider<RequestFingerprint> fingerprints,
                                       ObjectProvider<IdempotencyProperties> properties,
                                       ObjectProvider<IdempotencyMetrics> metrics, GrpcProblemRenderer renderer) {
        this.beans = beans;
        this.stores = stores;
        this.fingerprints = fingerprints;
        this.properties = properties;
        this.metrics = metrics;
        this.renderer = renderer;
    }

    @Override
    public Stage stage() {
        return Stage.REPLAY;
    }

    /**
     * Finds the methods that opted in, and refuses to start when one did and nothing can honour it.
     */
    @Override
    public void afterSingletonsInstantiated() {
        for (Object bean : beans.getBeansWithAnnotation(LudwigGrpcService.class).values()) {
            if (bean instanceof BindableService service) {
                Class<?> type = AopUtils.getTargetClass(bean);
                service.bindService().getMethods().forEach(method -> register(type, method));
            }
        }
        boolean usable = stores.getIfAvailable() != null && fingerprints.getIfAvailable() != null
                && properties.getIfAvailable() != null;
        if (!policies.isEmpty() && !usable) {
            throw new GrpcServerConfigurationException(
                    GrpcServerMessages.startup("idempotency-store-missing", policies.keySet()));
        }
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                       ServerCallHandler<Q, R> next) {
        Policy policy = policies.get(call.getMethodDescriptor().getFullMethodName());
        if (policy == null) {
            return next.startCall(call, headers);
        }
        String key = headers.get(GrpcMetadataKeys.IDEMPOTENCY_KEY);
        if (key == null || key.isBlank()) {
            if (policy.required()) {
                throw new IdempotencyKeyRequiredException();
            }
            return next.startCall(call, headers);
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IdempotencyKeyTooLongException(key.length(), MAX_KEY_LENGTH);
        }
        RecordingCall<Q, R> recording = new RecordingCall<>(call);
        return new ClaimingListener<>(next.startCall(recording, headers), recording, policy.scope(), key);
    }

    private void register(Class<?> type, ServerMethodDefinition<?, ?> definition) {
        MethodDescriptor<?, ?> descriptor = definition.getMethodDescriptor();
        Idempotent annotation = annotation(type, descriptor.getBareMethodName());
        if (annotation == null) {
            return;
        }
        if (descriptor.getType() != MethodDescriptor.MethodType.UNARY) {
            throw new GrpcServerConfigurationException(
                    GrpcServerMessages.startup("idempotent-not-unary", descriptor.getFullMethodName()));
        }
        String scope = StringUtils.hasText(annotation.scope()) ? annotation.scope() : descriptor.getFullMethodName();
        policies.put(descriptor.getFullMethodName(), new Policy(scope, annotation.required()));
    }

    /**
     * The annotation on the handler method - {@code Store} in the contract is {@code store} in the
     * generated base class. Looked up on the method only: a class-level annotation would opt in the
     * streaming methods beside it, which cannot be replayed.
     */
    private static Idempotent annotation(Class<?> type, String bareMethodName) {
        if (bareMethodName == null || bareMethodName.isEmpty()) {
            return null;
        }
        String javaName = Character.toLowerCase(bareMethodName.charAt(0)) + bareMethodName.substring(1);
        for (Method method : type.getMethods()) {
            if (method.getName().equals(javaName)) {
                Idempotent found = AnnotatedElementUtils.findMergedAnnotation(method, Idempotent.class);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static byte[] bytes(InputStream message) {
        try (message) {
            return message.readAllBytes();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private record Policy(String scope, boolean required) {
    }

    /**
     * Remembers the reply, and settles the claim when the call closes.
     */
    private final class RecordingCall<Q, R> extends ForwardingServerCall.SimpleForwardingServerCall<Q, R> {

        private volatile Runnable onSuccess;
        private volatile Runnable onFailure;
        private volatile R reply;

        RecordingCall(ServerCall<Q, R> delegate) {
            super(delegate);
        }

        void own(String scope, String key, UUID requestId) {
            IdempotencyStore store = stores.getObject();
            onSuccess = () -> store.complete(scope, key, requestId, StoredResponse.of(
                    STORED_STATUS, CONTENT_TYPE, bytes(getMethodDescriptor().streamResponse(reply))));
            onFailure = () -> {
                metrics.getIfAvailable(() -> IdempotencyMetrics.NOOP).failed(scope);
                store.fail(scope, key, requestId, "the gRPC call did not complete");
            };
        }

        /** Releases the key, unless the call has already been settled. */
        void release() {
            Runnable failure = onFailure;
            onSuccess = null;
            onFailure = null;
            if (failure != null) {
                failure.run();
            }
        }

        @Override
        public void sendMessage(R message) {
            reply = message;
            super.sendMessage(message);
        }

        @Override
        public void close(Status status, Metadata trailers) {
            Runnable success = onSuccess;
            if (status.isOk() && reply != null && success != null) {
                onSuccess = null;
                onFailure = null;
                success.run();
            } else {
                release();
            }
            super.close(status, trailers);
        }
    }

    /**
     * Claims the key once the request message is known, and either runs the handler or answers
     * for it.
     */
    private final class ClaimingListener<Q, R>
            extends ForwardingServerCallListener.SimpleForwardingServerCallListener<Q> {

        private final RecordingCall<Q, R> call;
        private final String scope;
        private final String key;
        private Q request;

        ClaimingListener(ServerCall.Listener<Q> delegate, RecordingCall<Q, R> call, String scope, String key) {
            super(delegate);
            this.call = call;
            this.scope = scope;
            this.key = key;
        }

        @Override
        public void onMessage(Q message) {
            request = message;
            super.onMessage(message);
        }

        @Override
        public void onHalfClose() {
            if (request == null) {
                // No message: the handler's own listener answers that, and there is nothing to claim.
                super.onHalfClose();
                return;
            }
            MethodDescriptor<Q, R> descriptor = call.getMethodDescriptor();
            String fingerprint = fingerprints.getObject().ofPayload(scope, bytes(descriptor.streamRequest(request)));
            IdempotencyProperties config = properties.getObject();
            UUID requestId = UUID.randomUUID();
            ClaimResult claim = stores.getObject().claim(new ClaimRequest(ClaimMode.STANDALONE, scope, key,
                    requestId, fingerprint, config.ttlFor(scope), config.leaseFor(scope)));
            IdempotencyMetrics counters = metrics.getIfAvailable(() -> IdempotencyMetrics.NOOP);
            if (claim.fingerprintMismatch(fingerprint)) {
                counters.fingerprintMismatch(scope);
                throw new FingerprintMismatchException(scope, key);
            }
            if (!claim.won()) {
                answerDuplicate(claim, descriptor, counters);
                return;
            }
            call.own(scope, key, requestId);
            boolean handed = false;
            try {
                super.onHalfClose();
                handed = true;
            } finally {
                if (!handed) {
                    // The handler threw. The translator closes the call from outside this stage,
                    // so the claim is released here or it would hold the key for its whole lease.
                    call.release();
                }
            }
        }

        @Override
        public void onCancel() {
            call.release();
            super.onCancel();
        }

        private void answerDuplicate(ClaimResult claim, MethodDescriptor<Q, R> descriptor,
                                     IdempotencyMetrics counters) {
            if (claim.outcome() == ClaimOutcome.IN_PROGRESS) {
                Duration remaining = Duration.between(Instant.now(), claim.expiresAt());
                Duration retryAfter = remaining.isNegative() || remaining.isZero() ? Duration.ofSeconds(1) : remaining;
                throw renderer.render(
                        new ClaimInProgressException(key, retryAfter), descriptor.getFullMethodName(), retryAfter);
            }
            StoredResponse stored = claim.replayable().orElseThrow(() -> new ClaimNotReplayableException(key));
            counters.replayed(scope);
            Metadata replayed = new Metadata();
            replayed.put(GrpcMetadataKeys.IDEMPOTENCY_REPLAYED, Boolean.TRUE.toString());
            call.sendHeaders(replayed);
            call.sendMessage(descriptor.parseResponse(new ByteArrayInputStream(stored.body())));
            call.close(Status.OK, new Metadata());
        }
    }
}
