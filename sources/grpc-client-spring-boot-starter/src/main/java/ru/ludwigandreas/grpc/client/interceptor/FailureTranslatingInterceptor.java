package ru.ludwigandreas.grpc.client.interceptor;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.function.Supplier;
import ru.ludwigandreas.grpc.client.error.GrpcClientAuthenticationException;
import ru.ludwigandreas.grpc.client.error.GrpcClientConnectionException;
import ru.ludwigandreas.grpc.client.error.GrpcClientException;
import ru.ludwigandreas.grpc.client.error.GrpcClientResponseException;
import ru.ludwigandreas.grpc.client.error.GrpcClientTimeoutException;
import ru.ludwigandreas.grpc.core.problem.DecodedProblem;
import ru.ludwigandreas.grpc.core.problem.GrpcStatusCodec;

/**
 * Gives every failed call a typed cause that names the client and the correlation id.
 *
 * <p>The status, its description and its trailers are left as they are, so whatever reads the
 * {@code StatusRuntimeException} a stub throws reads what the peer said. The type is added as the
 * cause, which is what {@link GrpcClientException#from} and web-core's problem registry read.
 */
public final class FailureTranslatingInterceptor implements ClientInterceptor {

    private final String clientName;
    private final GrpcStatusCodec codec;
    private final Supplier<String> correlationId;

    /**
     * Creates the interceptor.
     *
     * @param clientName    the client
     * @param codec         decodes a platform problem from the status details
     * @param correlationId the current correlation id
     */
    public FailureTranslatingInterceptor(String clientName, GrpcStatusCodec codec, Supplier<String> correlationId) {
        this.clientName = clientName;
        this.codec = codec;
        this.correlationId = correlationId;
    }

    @Override
    public <Q, R> ClientCall<Q, R> interceptCall(MethodDescriptor<Q, R> method, CallOptions options, Channel next) {
        String correlation = correlationId.get();
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
            @Override
            public void start(Listener<R> listener, Metadata headers) {
                super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(listener) {
                    @Override
                    public void onClose(Status status, Metadata trailers) {
                        super.onClose(status.isOk() ? status : status.withCause(typed(status, trailers, correlation)),
                                trailers);
                    }
                }, headers);
            }
        };
    }

    private GrpcClientException typed(Status status, Metadata trailers, String correlation) {
        if (status.getCause() instanceof GrpcClientException already) {
            return already;
        }
        StatusRuntimeException answer = status.asRuntimeException(trailers);
        return switch (status.getCode()) {
            case DEADLINE_EXCEEDED -> new GrpcClientTimeoutException(clientName, correlation, status.getCode(),
                    "the deadline passed; the request may have been processed", answer);
            case UNAUTHENTICATED -> new GrpcClientAuthenticationException(clientName, correlation, status.getCode(),
                    "the peer rejected the credential", answer);
            default -> {
                DecodedProblem problem = codec.decode(answer).orElseThrow();
                yield status.getCode() == Status.Code.UNAVAILABLE && !problem.fromPlatform()
                        ? new GrpcClientConnectionException(clientName, correlation, status.getCode(),
                                "no connection to the peer, or it broke before an answer", answer)
                        : new GrpcClientResponseException(clientName, correlation, problem, answer);
            }
        };
    }
}
