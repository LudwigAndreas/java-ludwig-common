package ru.ludwigandreas.restclient.core;

import java.util.Set;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Looks up a configured client by name.
 *
 * <p>The third of the three ways to consume a client, after declarative interfaces and injected
 * {@code RestClient}/{@code WebClient} beans. It is for the cases the other two cannot express: code
 * that chooses its dependency at runtime - a router, a migration that talks to both the old and the
 * new service, a test - and code in a component that cannot take another constructor argument.
 *
 * <p>Clients are built lazily and cached, so asking twice returns the same instance and asking for a
 * client nobody uses costs nothing. Every client carries the same pipeline whichever way it was
 * obtained; there is no "registry client" that behaves differently from an injected one.
 */
public interface RestClientRegistry {

    /**
     * The blocking client called {@code name}.
     *
     * @throws IllegalArgumentException if no such client is configured, listing the ones that are -
     *                                  a typo in a client name is otherwise a {@code null} several
     *                                  frames away
     * @throws IllegalStateException if {@code name} is configured as {@code mode: async}, because
     *                               handing back a blocking view of a reactive client would silently
     *                               block an event loop
     */
    RestClient rest(String name);

    /**
     * The reactive client called {@code name}.
     *
     * @throws IllegalArgumentException if no such client is configured
     * @throws IllegalStateException if {@code name} is configured as {@code mode: sync}
     */
    WebClient reactive(String name);

    /** Every configured client name, in declaration order. */
    Set<String> names();

    /** Whether {@code name} is configured. */
    boolean contains(String name);

    /** The runtime behind {@code name}, for tooling and tests that need to inspect it. */
    ClientRuntime runtime(String name);
}
