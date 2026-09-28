package ru.ludwigandreas.restclient.spi;

import java.util.concurrent.CompletableFuture;

/**
 * Contributes credentials to one named client's outgoing requests.
 *
 * <p>One instance per named client, created once at startup by a
 * {@link ClientAuthenticationProvider} and used for the lifetime of the context, so an
 * implementation is free to hold a cache, a lock or a scheduled refresh - and must be thread-safe,
 * because every caller of that client shares it.
 *
 * <p>It is invoked per <em>attempt</em>, inside the retry, not once per logical call. That is what
 * makes 401 -> refresh -> retry work at all: the second attempt asks again, sees
 * {@link AuthRequest#credentialRejected()}, and supplies a freshly minted token.
 */
public interface ClientAuthenticator {

    /**
     * Applies credentials to {@code request}, blocking if it must.
     *
     * <p>Called on the calling thread for a {@code sync} client. For an {@code async} client this
     * method is only reached through the default implementation of {@link #authenticateAsync},
     * which is why an implementation that can block is required to override that method.
     *
     * @throws RuntimeException if no usable credential can be obtained; the pipeline turns it into
     *                          a {@code RestClientAuthenticationException} naming the client, and
     *                          never into an anonymous call
     */
    void authenticate(AuthRequest request);

    /**
     * Applies credentials without blocking the calling thread.
     *
     * <p>The default runs {@link #authenticate} inline and completes, which is correct for every
     * authenticator that only reads a field - static bearer, basic, API key - and wrong for any
     * that may perform I/O. An authenticator that can reach the network on this path must override
     * this and return a future completed elsewhere; otherwise it blocks an event loop, and an
     * {@code async} client that blocks its event loop is worse than a {@code sync} one.
     */
    default CompletableFuture<Void> authenticateAsync(AuthRequest request) {
        try {
            authenticate(request);
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    /**
     * Whether a 401 should be answered by discarding the credential and retrying once.
     *
     * <p>False for anything static: re-sending the same wrong API key cannot succeed, and the extra
     * call only doubles the load while the answer stays 401. True for anything cached and
     * refreshable, where a 401 usually means the token expired earlier than its {@code exp} claimed -
     * a clock skew, a revocation, a rotated signing key.
     */
    default boolean refreshOnUnauthorized() {
        return false;
    }

    /** Drops any cached credential, so the next attempt mints a new one. */
    default void invalidate() {
    }

    /**
     * A short, credential-free description used in startup logs and error messages.
     *
     * <p>Whatever this returns is printed. It must never contain the credential, the token, or
     * anything that would identify one - a client id is acceptable, a client secret is not.
     */
    default String describe() {
        return getClass().getSimpleName();
    }
}
