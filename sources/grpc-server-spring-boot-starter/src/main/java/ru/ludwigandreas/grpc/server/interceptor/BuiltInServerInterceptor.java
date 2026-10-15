package ru.ludwigandreas.grpc.server.interceptor;

import io.grpc.ServerInterceptor;

/**
 * One stage of the platform's fixed server chain.
 *
 * <p>The chain's order is the declaration order of {@link Stage} and nothing else: there is no
 * {@code @Order} to set and no property to move a stage, because every other order has a concrete
 * defect. The exception translator is outside authentication so a rejected credential is rendered
 * like any other failure; observation is outermost so a refused call is still measured; the context
 * bridge is inside authentication because the principal is one of the things it binds.
 *
 * <p>Not an SPI. A service's own interceptor is a
 * {@link ru.ludwigandreas.grpc.server.spi.LudwigGrpcServerInterceptor}, which always runs after
 * every stage here.
 */
public interface BuiltInServerInterceptor extends ServerInterceptor {

    /**
     * Where this interceptor sits.
     *
     * @return the stage
     */
    Stage stage();

    /**
     * The stages, outermost first.
     */
    enum Stage {
        /** Tracing and the request timer. */
        OBSERVATION,
        /** The correlation id, resolved once and stored in the call's context. */
        CORRELATION,
        /** The per-service access log and the no-deadline counter. */
        ACCESS_LOG,
        /** Exception to gRPC status, through the platform's problem mapping. */
        EXCEPTION,
        /** Refusal of a call the handler pool had no room for. */
        ADMISSION,
        /** The caller's credential. */
        AUTHENTICATION,
        /** Thread-locals restored around every callback. */
        CONTEXT,
        /** Idempotent replay of an opted-in unary method. */
        REPLAY
    }
}
