package ru.ludwigandreas.grpc.server.interceptor;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.binder.grpc.DefaultGrpcServerObservationConvention;
import io.micrometer.core.instrument.binder.grpc.GrpcServerObservationContext;
import io.micrometer.core.instrument.binder.grpc.ObservationGrpcServerInterceptor;
import io.micrometer.observation.ObservationRegistry;

/**
 * Tracing and the request timer, from Micrometer's own gRPC observation, renamed to
 * {@code ludwig.grpcserver.requests{service,method,status}}.
 *
 * <p>Outermost, so a call refused by authentication or admission is still measured. Service and
 * method come from the contract, so the tags' cardinality is bounded by construction and needs no
 * cap.
 */
public final class ObservationInterceptor implements BuiltInServerInterceptor {

    /**
     * The timer's name.
     */
    public static final String REQUESTS = "ludwig.grpcserver.requests";

    private final ObservationGrpcServerInterceptor delegate;

    /**
     * Creates the interceptor.
     *
     * @param registry the application's observation registry
     */
    public ObservationInterceptor(ObservationRegistry registry) {
        this.delegate = new ObservationGrpcServerInterceptor(registry);
        this.delegate.setCustomConvention(new Convention());
    }

    @Override
    public Stage stage() {
        return Stage.OBSERVATION;
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                       ServerCallHandler<Q, R> next) {
        return delegate.interceptCall(call, headers, next);
    }

    private static final class Convention extends DefaultGrpcServerObservationConvention {

        @Override
        public String getName() {
            return REQUESTS;
        }

        @Override
        public KeyValues getLowCardinalityKeyValues(GrpcServerObservationContext context) {
            return KeyValues.of(
                    "service", String.valueOf(context.getServiceName()),
                    "method", String.valueOf(context.getMethodName()),
                    "status", context.getStatusCode() == null ? "UNKNOWN" : context.getStatusCode().name());
        }
    }
}
