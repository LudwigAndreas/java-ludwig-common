package ru.ludwigandreas.security.authn.mtls;

import org.springframework.security.core.AuthenticationException;

/**
 * A client certificate was presented but cannot be turned into a caller this service knows.
 *
 * <p>The message is a constant chosen at the throw site from a fixed set. It reaches the client, and a
 * precise one ("no partner registered for spiffe://partners/acme") would confirm which identities are
 * registered to anyone who can obtain a certificate from the same CA. The specific reason goes to the
 * log and the metrics, where it belongs.
 */
public class PartnerAuthenticationException extends AuthenticationException {

    public PartnerAuthenticationException(String message) {
        super(message);
    }
}
