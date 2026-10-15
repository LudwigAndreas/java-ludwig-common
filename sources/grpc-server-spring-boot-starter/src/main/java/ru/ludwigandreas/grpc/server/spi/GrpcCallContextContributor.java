package ru.ludwigandreas.grpc.server.spi;

import io.grpc.Context;
import io.grpc.Metadata;
import org.springframework.core.Ordered;
import ru.ludwigandreas.grpc.core.context.ThreadBinding;

/**
 * Puts one ambient fact about a call into its gRPC {@link Context}, and says how to bind it to a
 * thread.
 *
 * <p>The two halves are separate because they happen at different times. {@link #contribute} runs
 * once, when the call arrives, and reads the request metadata. {@link #binding} runs around every
 * callback of the call, on whichever thread delivers it, and must read only the context - the
 * metadata is long gone by then.
 *
 * <p>The platform ships contributors for the correlation id, the caller's locale and zone and the
 * idempotency key. A service adds its own - a tenant id, a feature flag set - by publishing a bean.
 */
public interface GrpcCallContextContributor extends Ordered {

    /**
     * Adds this contributor's fact to the context.
     *
     * @param context        the context so far
     * @param headers        the request metadata
     * @param fullMethodName the method being called, as {@code package.Service/Method}
     * @return {@code context}, or a child of it carrying the new value
     */
    Context contribute(Context context, Metadata headers, String fullMethodName);

    /**
     * How the fact is bound to a thread, or {@code null} when it only needs to be in the context.
     *
     * @return the binding, or {@code null}
     */
    default ThreadBinding binding() {
        return null;
    }

    @Override
    default int getOrder() {
        return 0;
    }
}
