package ru.ludwigandreas.restclient.auth.provider;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import ru.ludwigandreas.outboundtoken.CachingTokenSource;

/**
 * The machine-token cache of every client-credentials client, by client name, recorded as each client's
 * authenticator is built. It is what lets an operator discard a client's cached token - after the identity provider
 * rotated its keys, or a client's scopes changed - so that the next call mints a fresh one.
 */
public class ClientTokenSources {

    private final Map<String, CachingTokenSource> byClient = new ConcurrentHashMap<>();

    void record(String clientName, CachingTokenSource tokens) {
        byClient.put(clientName, tokens);
    }

    /**
     * Discards {@code clientName}'s cached token; its next call mints a new one.
     *
     * @param clientName the client
     * @return whether the client has a token cache here; false before its first call or when it does not use client
     *         credentials
     */
    public boolean invalidate(String clientName) {
        CachingTokenSource tokens = byClient.get(clientName);
        if (tokens == null) {
            return false;
        }
        tokens.invalidate();
        return true;
    }

    /**
     * The clients with a token cache on this replica.
     *
     * @return their names, sorted
     */
    public Set<String> clients() {
        return new TreeSet<>(byClient.keySet());
    }
}
