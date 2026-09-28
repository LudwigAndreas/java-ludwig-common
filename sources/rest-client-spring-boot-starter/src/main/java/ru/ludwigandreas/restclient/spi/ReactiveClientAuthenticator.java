package ru.ludwigandreas.restclient.spi;

import java.util.concurrent.CompletableFuture;
import reactor.core.publisher.Mono;

/**
 * A {@link ClientAuthenticator} that can authenticate inside a Reactor chain.
 *
 * <p>The {@link ClientAuthenticator#authenticateAsync} contract is a {@code CompletableFuture}, which
 * is deliberately framework-neutral and can carry a result across a thread boundary - but it cannot
 * carry a <em>Reactor subscriber context</em>. Anything that lives there is invisible to it, and the
 * security context of a reactive request is exactly such a thing. An authenticator that needs it -
 * token relay above all - implements this interface, and the reactive pipeline uses
 * {@link #authenticateReactive} in preference to the future-based method.
 *
 * <p>Implementing it is optional and only matters for {@code mode: async}. A sync-only authenticator
 * ignores it entirely.
 */
public interface ReactiveClientAuthenticator extends ClientAuthenticator {

    /**
     * Applies credentials within the caller's Reactor context.
     *
     * <p>Must not block. The {@code Mono} completes empty on success and errors with the reason
     * otherwise; the pipeline turns that error into a {@code RestClientAuthenticationException}.
     */
    Mono<Void> authenticateReactive(AuthRequest request);

    /**
     * Bridges the future-based contract onto the reactive one.
     *
     * <p>Present so that a {@code ReactiveClientAuthenticator} is still a complete
     * {@link ClientAuthenticator}. It is not the path the reactive pipeline takes, and subscribing
     * outside the original chain means the subscriber context is gone - which is the whole reason
     * this interface exists - so an authenticator reached this way may legitimately fail.
     */
    @Override
    default CompletableFuture<Void> authenticateAsync(AuthRequest request) {
        return authenticateReactive(request).then().toFuture();
    }
}
