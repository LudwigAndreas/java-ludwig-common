package ru.ludwigandreas.restclient.spi;

/**
 * Produces an answer when a call could not be made at all.
 *
 * <p>Named per client, or per method, by {@code resilience.fallback.handler} /
 * {@code resilience.fallback.methods}. It runs outside every decorator, so it sees a spent retry
 * budget, an open breaker, a refused bulkhead permit and a transport failure alike - and never a
 * response the peer actually sent. A 4xx is an answer; substituting cached data for a 403 is how an
 * authorization defect reaches production unnoticed.
 *
 * <p>A handler that cannot produce a sensible value must rethrow {@link FallbackContext#failure()}
 * rather than return {@code null}: a {@code null} where the caller expected a value turns a clear
 * "billing is down" into a {@code NullPointerException} three frames away.
 */
@FunctionalInterface
public interface FallbackHandler {

    /**
     * The substitute answer, assignable to {@link FallbackContext#returnType()}.
     *
     * <p>For an {@code async} client the return type is the reactive one, so a handler returns a
     * {@code Mono}/{@code Flux}/{@code CompletableFuture}, and must not block while building it.
     *
     * @throws RuntimeException to let the original failure - or a better one - reach the caller
     */
    Object fallback(FallbackContext context);
}
