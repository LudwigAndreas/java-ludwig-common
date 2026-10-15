package ru.ludwigandreas.grpc.server.interceptor;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import java.util.List;
import java.util.function.Supplier;
import ru.ludwigandreas.grpc.core.context.GrpcCallContext;
import ru.ludwigandreas.grpc.core.context.ThreadBinding;
import ru.ludwigandreas.grpc.core.metadata.GrpcMetadataKeys;
import ru.ludwigandreas.grpc.server.spi.GrpcCallContextContributor;
import ru.ludwigandreas.observability.correlation.CorrelationContext;
import ru.ludwigandreas.observability.correlation.CorrelationIdResolver;

/**
 * Resolves the call's correlation id once, with the same resolver and the same acceptance rules
 * the HTTP filter uses, and opens it in the MDC around every callback.
 *
 * <p>The two halves sit at different depths on purpose. The id goes into the context near the top
 * of the chain, so the access log and a rendered failure carry it even for a call that never
 * reaches a handler; the MDC binding is applied by the context bridge, with the other bindings.
 */
public final class CorrelationInterceptor implements BuiltInServerInterceptor, GrpcCallContextContributor {

    /**
     * The contributor order: after the principal, before the preferences.
     */
    public static final int ORDER = -200;

    private final CorrelationContext correlation;
    private final CorrelationIdResolver resolver;
    private final Supplier<String> traceId;
    private final List<Metadata.Key<String>> inbound;

    /**
     * Creates the interceptor.
     *
     * @param correlation    the MDC scope
     * @param resolver       accepts an inbound id or generates one
     * @param traceId        the current trace id, or {@code null}
     * @param inboundHeaders the HTTP header names an id is accepted under, most preferred first
     */
    public CorrelationInterceptor(CorrelationContext correlation, CorrelationIdResolver resolver,
                                  Supplier<String> traceId, List<String> inboundHeaders) {
        this.correlation = correlation;
        this.resolver = resolver;
        this.traceId = traceId;
        this.inbound = inboundHeaders.stream().map(GrpcMetadataKeys::forHeader).toList();
    }

    @Override
    public Stage stage() {
        return Stage.CORRELATION;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                       ServerCallHandler<Q, R> next) {
        String id = resolver.resolve(inbound.stream().map(headers::get).toList(), traceId);
        if (id == null) {
            return next.startCall(call, headers);
        }
        return Contexts.interceptCall(
                Context.current().withValue(GrpcCallContext.CORRELATION_ID, id), call, headers, next);
    }

    @Override
    public Context contribute(Context context, Metadata headers, String fullMethodName) {
        return context;
    }

    @Override
    public ThreadBinding binding() {
        return context -> {
            String id = GrpcCallContext.CORRELATION_ID.get(context);
            return id == null ? ThreadBinding.Scope.NOOP : correlation.open(id)::close;
        };
    }
}
