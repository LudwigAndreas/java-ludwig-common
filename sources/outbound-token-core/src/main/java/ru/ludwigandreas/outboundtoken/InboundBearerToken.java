package ru.ludwigandreas.outboundtoken;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.AbstractOAuth2Token;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;

/**
 * The bearer token of the request being served, for relaying it to a downstream service.
 *
 * <p>Read on the caller's thread, from the security context. It finds nothing for an
 * authentication that keeps no token - the platform's own {@code LudwigAuthentication} keeps none on
 * purpose - and a transport that serves calls with such an authentication supplies the token from
 * its own call context first; the gRPC client starter does.
 */
public final class InboundBearerToken {

    private InboundBearerToken() {
    }

    /**
     * The current request's bearer token.
     *
     * @return the token, or {@code null} outside an authenticated request or when the
     *         authentication carries none
     */
    public static String current() {
        return of(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * The bearer token an authentication carries - for a caller that found the authentication
     * somewhere other than the thread's security context, such as a reactive context.
     *
     * @param authentication the authentication, or {@code null}
     * @return the token, or {@code null} when it is absent, unauthenticated or carries none
     */
    public static String of(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        if (authentication instanceof AbstractOAuth2TokenAuthenticationToken<?> token) {
            return token.getToken().getTokenValue();
        }
        if (authentication.getCredentials() instanceof AbstractOAuth2Token token) {
            return token.getTokenValue();
        }
        return null;
    }
}
