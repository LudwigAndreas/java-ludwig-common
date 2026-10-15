package ru.ludwigandreas.grpc.server.spi;

import io.grpc.ServerInterceptor;
import org.springframework.core.Ordered;

/**
 * A service's own server interceptor.
 *
 * <p>Publish a bean of this type and it runs on every call, <b>after</b> the platform's chain and
 * immediately before the handler. That position is not configurable: by the time one of these
 * runs the call has been measured, the caller authenticated, the correlation id and preferences
 * bound to the thread and the failure mapping armed - so an interceptor here can rely on all of it
 * and cannot disable any of it.
 *
 * <p>A plain {@code ServerInterceptor} bean is deliberately <em>not</em> picked up. An interceptor
 * that some library registered for another purpose would otherwise join every service's chain by
 * accident.
 *
 * <p>Several of these run in {@link #getOrder()} order, lowest first.
 */
public interface LudwigGrpcServerInterceptor extends ServerInterceptor, Ordered {

    @Override
    default int getOrder() {
        return 0;
    }
}
