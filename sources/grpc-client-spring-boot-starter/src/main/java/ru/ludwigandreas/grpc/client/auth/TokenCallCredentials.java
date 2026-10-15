package ru.ludwigandreas.grpc.client.auth;

import io.grpc.CallCredentials;
import io.grpc.CallOptions;
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.concurrent.Executor;
import java.util.function.Function;
import ru.ludwigandreas.grpc.core.metadata.GrpcMetadataKeys;
import ru.ludwigandreas.grpc.client.error.GrpcClientAuthenticationException;

/**
 * Attaches a bearer token to each attempt, on gRPC's executor.
 *
 * <p>When no token can be had the attempt is failed before anything is sent: the call never goes
 * out anonymously, which is the fail-closed rule for relay and for a token endpoint that is down.
 */
public final class TokenCallCredentials extends CallCredentials {

    /**
     * Set on a call to mint a new token instead of reusing the cached one.
     */
    public static final CallOptions.Key<Boolean> FORCE_REFRESH = CallOptions.Key.createWithDefault(
            "ludwig-grpc-force-token-refresh", Boolean.FALSE);

    /**
     * The caller's own token, captured on the caller's thread for relay.
     */
    public static final CallOptions.Key<String> RELAY_TOKEN = CallOptions.Key.create("ludwig-grpc-relay-token");

    private final String clientName;
    private final Function<CallOptions, String> tokens;

    /**
     * Creates the credentials.
     *
     * @param clientName the client
     * @param tokens     the token for a call; {@code null} or an exception means there is none
     */
    public TokenCallCredentials(String clientName, Function<CallOptions, String> tokens) {
        this.clientName = clientName;
        this.tokens = tokens;
    }

    @Override
    public void applyRequestMetadata(RequestInfo request, Executor executor, MetadataApplier applier) {
        executor.execute(() -> {
            // CHECKSTYLE.OFF: IllegalCatch - any failure to obtain a token must fail the attempt, not the executor
            try {
                String token = tokens.apply(request.getCallOptions());
                if (token == null) {
                    fail(applier, "no access token to send", null);
                    return;
                }
                Metadata headers = new Metadata();
                headers.put(GrpcMetadataKeys.AUTHORIZATION, "Bearer " + token);
                applier.apply(headers);
            } catch (RuntimeException unavailable) {
                fail(applier, "no access token could be obtained", unavailable);
            }
            // CHECKSTYLE.ON: IllegalCatch
        });
    }

    private void fail(MetadataApplier applier, String reason, Throwable cause) {
        applier.fail(Status.UNAUTHENTICATED.withDescription(reason).withCause(new GrpcClientAuthenticationException(
                clientName, null, Status.Code.UNAUTHENTICATED, reason + "; the call was not sent", cause)));
    }
}
