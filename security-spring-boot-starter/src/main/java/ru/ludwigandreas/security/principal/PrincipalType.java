package ru.ludwigandreas.security.principal;

/**
 * Which of the three ways into a service the caller came through.
 *
 * <p>The distinction is not cosmetic: it decides where the caller's roles come from, which data
 * scope applies, and what an audit record has to say about who acted. Keeping it on the principal
 * means a policy can say "partners may never read this field" without re-deriving that fact from
 * the transport at every check.
 */
public enum PrincipalType {

    /**
     * A human, reaching the service through the web UI. The browser holds an opaque session cookie;
     * the edge exchanges it for a short-lived JWT and forwards that. The JWT carries identity only -
     * roles are resolved locally (see {@link ru.ludwigandreas.security.authz.AuthorityResolver}).
     */
    USER,

    /**
     * An external organization calling the REST API directly, authenticated by a client certificate
     * that Envoy terminated and described in {@code x-forwarded-client-cert}. Partners are granted
     * access explicitly and their data scope is normally pinned to their own rows.
     */
    PARTNER,

    /**
     * Another service inside the mesh, authenticated by its workload certificate (SPIFFE id). Used
     * for background and fan-out calls that carry no end user; a service identity gets exactly the
     * roles its own grant says, never a user's.
     */
    SERVICE
}
