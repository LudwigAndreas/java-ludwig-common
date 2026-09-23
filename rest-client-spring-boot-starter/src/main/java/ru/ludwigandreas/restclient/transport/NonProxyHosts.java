package ru.ludwigandreas.restclient.transport;

import java.util.List;
import java.util.Locale;

/**
 * Decides whether a host bypasses the configured proxy.
 *
 * <p>Implements the same two forms the JDK's {@code http.nonProxyHosts} understands and nothing
 * more: an exact host name, and a {@code *.suffix} wildcard. A richer matcher - CIDR ranges, regular
 * expressions - was considered and rejected: this list is written by whoever writes the deployment
 * manifest, it is read by whoever debugs an egress problem at 3am, and both of them already know
 * these two forms from every other tool in the stack.
 *
 * <p>Matching is case-insensitive because host names are.
 */
public final class NonProxyHosts {

    private final List<String> patterns;

    public NonProxyHosts(List<String> patterns) {
        this.patterns = patterns == null ? List.of()
                : patterns.stream().map(p -> p.toLowerCase(Locale.ROOT)).toList();
    }

    /** Whether {@code host} is reached directly rather than through the proxy. */
    public boolean bypasses(String host) {
        if (host == null || patterns.isEmpty()) {
            return false;
        }
        String candidate = host.toLowerCase(Locale.ROOT);
        for (String pattern : patterns) {
            if (matches(pattern, candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean matches(String pattern, String host) {
        if (pattern.startsWith("*.")) {
            String suffix = pattern.substring(1);
            // "*.internal" matches "billing.internal" and also the bare "internal", which is what
            // every other implementation of this syntax does.
            return host.endsWith(suffix) || host.equals(pattern.substring(2));
        }
        return pattern.equals(host);
    }
}
