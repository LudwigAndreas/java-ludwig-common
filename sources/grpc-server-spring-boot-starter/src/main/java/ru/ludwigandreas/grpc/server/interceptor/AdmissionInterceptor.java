package ru.ludwigandreas.grpc.server.interceptor;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import ru.ludwigandreas.grpc.server.error.GrpcServerSaturatedException;
import ru.ludwigandreas.grpc.server.server.BoundedCallExecutor;

/**
 * Refuses a call the handler pool had no room for, before anything is done for it.
 *
 * <p>Inside the exception translator so the refusal is rendered like any other problem, and
 * outside authentication so a saturated server does not spend a token validation on a call it is
 * about to turn away.
 */
public final class AdmissionInterceptor implements BuiltInServerInterceptor {

    @Override
    public Stage stage() {
        return Stage.ADMISSION;
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                       ServerCallHandler<Q, R> next) {
        if (BoundedCallExecutor.onOverflowThread()) {
            throw new GrpcServerSaturatedException();
        }
        return next.startCall(call, headers);
    }
}
