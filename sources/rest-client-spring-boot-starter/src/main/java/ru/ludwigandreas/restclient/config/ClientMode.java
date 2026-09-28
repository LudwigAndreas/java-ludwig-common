package ru.ludwigandreas.restclient.config;

/**
 * Which execution engine backs a named client, and therefore what its declarative interfaces may
 * return.
 */
public enum ClientMode {

    /**
     * Blocking {@code RestClient} over a {@link TransportEngine}. The default, because the
     * overwhelming majority of calls in this estate are made from a servlet request thread that is
     * going to block on the answer anyway, and a reactive stack there buys nothing while costing
     * every developer a debugging story.
     */
    SYNC,

    /**
     * Non-blocking {@code WebClient} over Reactor Netty. Declarative interfaces may return
     * {@code Mono}, {@code Flux} or {@code CompletableFuture}; the time limiter becomes meaningful,
     * and no call occupies a thread while it waits.
     */
    ASYNC
}
