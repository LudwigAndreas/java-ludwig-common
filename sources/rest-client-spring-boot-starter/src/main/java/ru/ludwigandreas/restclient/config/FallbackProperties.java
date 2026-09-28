package ru.ludwigandreas.restclient.config;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * Where a named client turns when the call could not be made.
 *
 * <p>A fallback runs only for a failure the pipeline produced - a spent retry budget, an open
 * breaker, a rejected bulkhead permit, a transport error. A 4xx that the peer answered is a real
 * answer and is never faked into a fallback: hiding a 403 behind stale data is how an authorization
 * bug ships.
 */
@Getter
@Setter
public class FallbackProperties {

    /**
     * Bean name of a {@code FallbackHandler} used for every method of this client.
     *
     * <p>A bean name rather than a class, so the handler is an ordinary Spring bean with its own
     * dependencies - a cache, a repository, a feature flag - instead of something this starter has
     * to instantiate.
     */
    private String handler;

    /**
     * Per-method overrides, keyed by {@code Interface#method} or by the bare method name.
     *
     * <p>Most clients want one fallback for the read paths and none for the writes, which is
     * exactly what a per-method map expresses and a single handler cannot.
     */
    private Map<String, String> methods = new LinkedHashMap<>();
}
