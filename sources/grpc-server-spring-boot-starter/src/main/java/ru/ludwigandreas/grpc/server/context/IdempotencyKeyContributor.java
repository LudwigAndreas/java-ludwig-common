package ru.ludwigandreas.grpc.server.context;

import io.grpc.Context;
import io.grpc.Metadata;
import ru.ludwigandreas.grpc.core.context.GrpcCallContext;
import ru.ludwigandreas.grpc.core.metadata.GrpcMetadataKeys;
import ru.ludwigandreas.grpc.server.spi.GrpcCallContextContributor;

/**
 * Makes the caller's idempotency key readable from the call's context, for a handler that passes
 * it on to an application service.
 */
public final class IdempotencyKeyContributor implements GrpcCallContextContributor {

    @Override
    public Context contribute(Context context, Metadata headers, String fullMethodName) {
        String key = headers.get(GrpcMetadataKeys.IDEMPOTENCY_KEY);
        return key == null || key.isBlank() ? context : context.withValue(GrpcCallContext.IDEMPOTENCY_KEY, key);
    }
}
