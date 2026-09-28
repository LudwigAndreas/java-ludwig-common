package ru.ludwigandreas.restclient.spi;

/**
 * Supplies a bearer token that this starter does not know how to obtain.
 *
 * <p>The escape hatch for the bearer scheme: {@code auth.token-supplier: myTokenSupplier} names a
 * bean of this type, and the client asks it for a token on every attempt. It exists so that a
 * partner with a bespoke token endpoint, or a token that arrives from a sidecar, does not require a
 * whole {@link ClientAuthenticationProvider}.
 *
 * <p>Called on the request path, so an implementation that reaches the network must do its own
 * caching. Returning {@code null} or blank fails the call with a
 * {@code RestClientAuthenticationException} rather than sending an empty {@code Authorization}
 * header, which some servers accept as anonymous.
 */
@FunctionalInterface
public interface TokenSupplier {

    /**
     * The current token for {@code clientName}, without the {@code Bearer} prefix.
     *
     * @param forceRefresh {@code true} when the previous token was rejected with 401, meaning any
     *                     cached value must be discarded
     */
    String token(String clientName, boolean forceRefresh);
}
