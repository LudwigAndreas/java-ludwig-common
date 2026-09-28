package ru.ludwigandreas.restclient.config;

import java.util.Locale;
import java.util.Set;

/**
 * The {@code auth.type} values this starter ships, as constants.
 *
 * <p>They are strings and not an enum because the type is an open set: a service adds one by
 * publishing a {@code ClientAuthenticationProvider} bean whose {@code type()} this module has never
 * heard of, and an enum would make that impossible. These constants exist so the built-in providers
 * and the validator agree on the spelling.
 */
public final class AuthTypes {

    /** No credentials. The default. */
    public static final String NONE = "none";
    /** HTTP Basic. */
    public static final String BASIC = "basic";
    /** A static bearer token, or one from a {@code TokenSupplier} bean. */
    public static final String BEARER = "bearer";
    /** A key in a header or a query parameter. */
    public static final String API_KEY = "api-key";
    /** OAuth2 client credentials against a Spring Security {@code ClientRegistration}. */
    public static final String OAUTH2_CLIENT_CREDENTIALS = "oauth2-client-credentials";
    /** Propagate the inbound user's access token. */
    public static final String OAUTH2_TOKEN_RELAY = "oauth2-token-relay";
    /** Delegate to a named {@code ClientAuthenticator} bean. */
    public static final String CUSTOM = "custom";

    /**
     * Types whose credential is cached and can be re-minted, and which therefore default to
     * retrying once after a 401.
     */
    private static final Set<String> REFRESHABLE =
            Set.of(OAUTH2_CLIENT_CREDENTIALS, OAUTH2_TOKEN_RELAY);

    private AuthTypes() {
    }

    /** Whether a 401 is worth answering with a forced refresh and one more attempt. */
    public static boolean isRefreshable(String type) {
        return type != null && REFRESHABLE.contains(type.toLowerCase(Locale.ROOT));
    }
}
