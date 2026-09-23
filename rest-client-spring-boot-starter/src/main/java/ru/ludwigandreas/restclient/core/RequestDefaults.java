package ru.ludwigandreas.restclient.core;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Applies a client's default query parameters to a request URI.
 *
 * <p>Default <em>headers</em> are the builder's job - both {@code RestClient} and {@code WebClient}
 * have a first-class notion of them. Default query parameters have no such notion in either, so they
 * are applied here, once, in a place both pipelines reach.
 *
 * <p>A parameter the call already set wins. That is the fourth-versus-fifth precedence rule from the
 * README made concrete: configuration supplies a default, and the call site overrides it - a client
 * configured with {@code api-version=2} can still be asked for version 3 by one call that needs it.
 */
public final class RequestDefaults {

    private RequestDefaults() {
    }

    /** {@code uri} with any of {@code defaults} it does not already carry. */
    public static URI withQueryParams(URI uri, Map<String, List<String>> defaults) {
        if (defaults == null || defaults.isEmpty()) {
            return uri;
        }
        UriComponentsBuilder builder = UriComponentsBuilder.fromUri(uri);
        var existing = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
        boolean changed = false;
        for (Map.Entry<String, List<String>> entry : defaults.entrySet()) {
            if (existing.containsKey(entry.getKey())) {
                continue;
            }
            builder.queryParam(entry.getKey(), entry.getValue().toArray());
            changed = true;
        }
        // Returning the original instance when nothing was added keeps the common case allocation-free
        // and, more importantly, avoids re-encoding a URI that was already correct.
        return changed ? builder.build(true).toUri() : uri;
    }
}
