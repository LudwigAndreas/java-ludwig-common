package ru.ludwigandreas.grpc.client.interceptor;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.MethodDescriptor;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.binder.grpc.DefaultGrpcClientObservationConvention;
import io.micrometer.core.instrument.binder.grpc.GrpcClientObservationContext;
import io.micrometer.core.instrument.binder.grpc.ObservationGrpcClientInterceptor;
import io.micrometer.observation.ObservationRegistry;

/**
 * Tracing and the request timer, from Micrometer's own gRPC observation, renamed to
 * {@code ludwig.grpcclient.requests{client,service,method,status}}. Outermost, so a call refused by
 * this client's own policy is still measured.
 */
public final class ObservationInterceptor implements ClientInterceptor {

    /**
     * The timer's name.
     */
    public static final String REQUESTS = "ludwig.grpcclient.requests";

    private final ObservationGrpcClientInterceptor delegate;

    /**
     * Creates the interceptor.
     *
     * @param clientName the client, as the {@code client} tag
     * @param registry   the application's observation registry
     */
    public ObservationInterceptor(String clientName, ObservationRegistry registry) {
        this.delegate = new ObservationGrpcClientInterceptor(registry);
        this.delegate.setCustomConvention(new DefaultGrpcClientObservationConvention() {
            @Override
            public String getName() {
                return REQUESTS;
            }

            @Override
            public KeyValues getLowCardinalityKeyValues(GrpcClientObservationContext context) {
                return KeyValues.of(
                        "client", clientName,
                        "service", String.valueOf(context.getServiceName()),
                        "method", String.valueOf(context.getMethodName()),
                        "status", context.getStatusCode() == null ? "UNKNOWN" : context.getStatusCode().name());
            }
        });
    }

    @Override
    public <Q, R> ClientCall<Q, R> interceptCall(MethodDescriptor<Q, R> method, CallOptions options, Channel next) {
        return delegate.interceptCall(method, options, next);
    }
}
