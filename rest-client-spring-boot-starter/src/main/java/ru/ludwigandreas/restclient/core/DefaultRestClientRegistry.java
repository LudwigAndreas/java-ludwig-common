package ru.ludwigandreas.restclient.core;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import ru.ludwigandreas.restclient.config.ClientMode;

/**
 * The registry: builds each named client once, on first use, and hands out the same instance after
 * that.
 *
 * <h2>Lazy, and why</h2>
 *
 * <p>Building a client opens a keystore, may contact an authorization server, and creates a
 * connection pool. Doing all of that at startup for a client that a particular deployment never
 * calls would turn an unused dependency's misconfiguration into a failed rollout. Building on first
 * use also means a test slice that touches one client does not need the other nine to be reachable.
 *
 * <p>The trade is that a configuration error in an unused client surfaces on its first call rather
 * than at startup. {@code RestClientConfigurationValidator} is what closes that gap: it checks every
 * declared client's configuration at startup without building any of them.
 */
public class DefaultRestClientRegistry implements RestClientRegistry {

    private final Map<String, ClientMode> modes;
    private final Function<String, ClientRuntime> runtimeFactory;
    private final Function<ClientRuntime, RestClient> restFactory;
    private final Function<ClientRuntime, WebClient> webFactory;

    private final Map<String, ClientRuntime> runtimes = new ConcurrentHashMap<>();
    private final Map<String, RestClient> restClients = new ConcurrentHashMap<>();
    private final Map<String, WebClient> webClients = new ConcurrentHashMap<>();
    /** Creates the registry over the configured client modes and the factories that build them. */
    public DefaultRestClientRegistry(Map<String, ClientMode> modes,
                                     Function<String, ClientRuntime> runtimeFactory,
                                     Function<ClientRuntime, RestClient> restFactory,
                                     Function<ClientRuntime, WebClient> webFactory) {
        this.modes = Map.copyOf(modes);
        this.runtimeFactory = runtimeFactory;
        this.restFactory = restFactory;
        this.webFactory = webFactory;
    }

    @Override
    public RestClient rest(String name) {
        require(name, ClientMode.SYNC);
        // computeIfAbsent, not get-then-put: two threads racing on the first call to a client must
        // not each build a pool, and the loser's pool must not be quietly discarded with its sockets.
        return restClients.computeIfAbsent(name, key -> restFactory.apply(runtime(key)));
    }

    @Override
    public WebClient reactive(String name) {
        require(name, ClientMode.ASYNC);
        return webClients.computeIfAbsent(name, key -> webFactory.apply(runtime(key)));
    }

    @Override
    public Set<String> names() {
        return new LinkedHashSet<>(modes.keySet());
    }

    @Override
    public boolean contains(String name) {
        return modes.containsKey(name);
    }

    @Override
    public ClientRuntime runtime(String name) {
        return runtimes.computeIfAbsent(name, runtimeFactory);
    }

    private void require(String name, ClientMode expected) {
        ClientMode mode = modes.get(name);
        if (mode == null) {
            throw new IllegalArgumentException("No REST client named '" + name + "' is configured. "
                    + "Configured clients: " + String.join(", ", modes.keySet())
                    + ". Declare it under ludwig.rest-client.clients.");
        }
        if (mode != expected) {
            throw new IllegalStateException("Client '" + name + "' is mode=" + mode.name().toLowerCase(
                    java.util.Locale.ROOT) + ". Ask for it with "
                    + (expected == ClientMode.SYNC ? "registry.reactive(...)" : "registry.rest(...)")
                    + " instead, or change its mode - a blocking view of a reactive client would "
                    + "block an event loop, and a reactive view of a blocking one does not exist.");
        }
    }
}
