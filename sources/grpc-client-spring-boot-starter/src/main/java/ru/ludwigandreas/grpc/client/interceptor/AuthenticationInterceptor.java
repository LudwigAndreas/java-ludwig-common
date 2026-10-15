package ru.ludwigandreas.grpc.client.interceptor;

import io.grpc.CallCredentials;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.util.function.Supplier;
import ru.ludwigandreas.grpc.client.auth.TokenCallCredentials;

/**
 * Attaches the client's credentials, captures the caller's token for relay, and - for a unary call
 * with a machine token - makes one more attempt when the peer rejects it.
 *
 * <p>The one re-attempt is outside gRPC's retry budget and inside the deadline: a peer that rejects
 * a cached token usually means the token was revoked or the peer's keys rotated, and minting a new
 * one is the fix. A second rejection is reported, as an authentication failure.
 */
public final class AuthenticationInterceptor implements ClientInterceptor {

    private final CallCredentials credentials;
    private final Supplier<String> relayToken;
    private final boolean reattempt;

    /**
     * Creates the interceptor.
     *
     * @param credentials the credentials, or {@code null} for none
     * @param relayToken  reads the caller's token on the caller's thread, or {@code null} when not relaying
     * @param reattempt   whether a rejected token is minted again once
     */
    public AuthenticationInterceptor(CallCredentials credentials, Supplier<String> relayToken, boolean reattempt) {
        this.credentials = credentials;
        this.relayToken = relayToken;
        this.reattempt = reattempt;
    }

    @Override
    public <Q, R> ClientCall<Q, R> interceptCall(MethodDescriptor<Q, R> method, CallOptions options, Channel next) {
        if (credentials == null) {
            return next.newCall(method, options);
        }
        CallOptions authenticated = options.withCallCredentials(credentials);
        String token = relayToken == null ? null : relayToken.get();
        if (token != null) {
            // No token leaves the option unset, and the credentials then fail the call unsent.
            authenticated = authenticated.withOption(TokenCallCredentials.RELAY_TOKEN, token);
        }
        if (reattempt && method.getType() == MethodDescriptor.MethodType.UNARY) {
            return new ReattemptingCall<>(method, authenticated, next);
        }
        return next.newCall(method, authenticated);
    }

    /**
     * Replays a unary request once, with a freshly minted token, if the first attempt is rejected.
     */
    private static final class ReattemptingCall<Q, R> extends ClientCall<Q, R> {

        private final MethodDescriptor<Q, R> method;
        private final CallOptions options;
        private final Channel next;
        private Listener<R> listener;
        private Metadata headers;
        private Q message;
        private int requested;
        private boolean halfClosed;
        private volatile boolean cancelled;
        private volatile ClientCall<Q, R> current;

        ReattemptingCall(MethodDescriptor<Q, R> method, CallOptions options, Channel next) {
            this.method = method;
            this.options = options;
            this.next = next;
        }

        @Override
        public void start(Listener<R> responseListener, Metadata requestHeaders) {
            this.listener = responseListener;
            this.headers = copy(requestHeaders);
            current = next.newCall(method, options);
            current.start(new Attempt(true), requestHeaders);
        }

        @Override
        public void request(int count) {
            requested += count;
            current.request(count);
        }

        @Override
        public void sendMessage(Q request) {
            message = request;
            current.sendMessage(request);
        }

        @Override
        public void halfClose() {
            halfClosed = true;
            current.halfClose();
        }

        @Override
        public void cancel(String reason, Throwable cause) {
            cancelled = true;
            current.cancel(reason, cause);
        }

        @Override
        public boolean isReady() {
            return current.isReady();
        }

        private void reattempt() {
            ClientCall<Q, R> again = next.newCall(method, options.withOption(TokenCallCredentials.FORCE_REFRESH, true));
            current = again;
            again.start(new Attempt(false), copy(headers));
            again.request(Math.max(requested, 1));
            again.sendMessage(message);
            again.halfClose();
        }

        private static Metadata copy(Metadata original) {
            Metadata copy = new Metadata();
            copy.merge(original);
            return copy;
        }

        private final class Attempt extends ClientCall.Listener<R> {

            private final boolean first;
            private Metadata held;

            Attempt(boolean first) {
                this.first = first;
            }

            @Override
            public void onHeaders(Metadata responseHeaders) {
                // The first attempt's headers are held: if it is rejected they belong to an
                // attempt the caller never sees.
                if (first) {
                    held = responseHeaders;
                } else {
                    listener.onHeaders(responseHeaders);
                }
            }

            @Override
            public void onMessage(R response) {
                release();
                listener.onMessage(response);
            }

            @Override
            public void onClose(Status status, Metadata trailers) {
                boolean rejected = status.getCode() == Status.Code.UNAUTHENTICATED;
                if (first && rejected && !cancelled && halfClosed && message != null) {
                    reattempt();
                    return;
                }
                release();
                listener.onClose(status, trailers);
            }

            @Override
            public void onReady() {
                listener.onReady();
            }

            private void release() {
                if (held != null) {
                    listener.onHeaders(held);
                    held = null;
                }
            }
        }
    }
}
