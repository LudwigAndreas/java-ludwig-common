package ru.ludwigandreas.grpc.server.interceptor;

import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import ru.ludwigandreas.grpc.core.context.ContextRestoringServerInterceptor;
import ru.ludwigandreas.grpc.server.spi.GrpcCallContextContributor;

/**
 * Collects the call's ambient facts into its context and restores them into thread-locals around
 * every callback. The restoring itself, and why once-per-call is wrong, is
 * {@link ContextRestoringServerInterceptor}.
 */
public final class ContextBridgeInterceptor implements BuiltInServerInterceptor {

    private final CallContextContributors contributors;

    /**
     * Creates the interceptor.
     *
     * @param contributors what goes into the context and how each fact is bound to a thread
     */
    public ContextBridgeInterceptor(CallContextContributors contributors) {
        this.contributors = contributors;
    }

    @Override
    public Stage stage() {
        return Stage.CONTEXT;
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                       ServerCallHandler<Q, R> next) {
        Context context = Context.current();
        String method = call.getMethodDescriptor().getFullMethodName();
        for (GrpcCallContextContributor contributor : contributors.contributors()) {
            context = contributor.contribute(context, headers, method);
        }
        CallState state = CallState.KEY.get(context);
        if (state != null) {
            state.bridged(context);
        }
        return ContextRestoringServerInterceptor.interceptCall(context, contributors.bindings(), call, headers, next);
    }
}
