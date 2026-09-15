package ru.ludwigandreas.security.web;

import java.util.List;

/**
 * Headers that carry identity, and are therefore only ever allowed to come from the edge.
 *
 * <p>Collected in one place because they all share one rule: they are facts the infrastructure
 * asserts, never input a client may supply. {@link IdentityHeaderStrippingFilter} enforces that.
 */
public final class SecurityHeaders {

    /** Envoy's verified client-certificate description. */
    public static final String XFCC = "x-forwarded-client-cert";

    /** Correlation id propagated across services; echoed into the audit trail and logs. */
    public static final String REQUEST_ID = "x-request-id";

    /** Conventional names some gateways use to forward a resolved user; never trusted as identity. */
    public static final List<String> CLIENT_FORBIDDEN = List.of(
            XFCC,
            "x-forwarded-user",
            "x-forwarded-email",
            "x-forwarded-groups",
            "x-auth-request-user",
            "x-auth-request-email",
            "x-auth-request-groups",
            "x-ludwig-subject",
            "x-ludwig-roles",
            "x-ludwig-tenant");

    private SecurityHeaders() {
    }
}
