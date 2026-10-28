package ru.ludwigandreas.pat.web;

/**
 * The authority the self-service management API requires.
 *
 * <p>Only the caller's own tokens. Minting a credential for somebody else is a different act with a different
 * blast radius - it is how a long-lived automation credential outlives the person who set it up, and how somebody
 * could mint a credential that acts as a colleague - so it is not an authority here at all: it is the
 * {@code pat.privileged} operation of the administrative surface, granted by the service.
 *
 * <p>A permission rather than a role, so a deployment can attach it to whatever role structure it already
 * has. {@code LudwigPrincipal} keeps roles and permissions separate for exactly this - a fine-grained
 * entitlement that does not deserve a role of its own.
 */
public final class PatAuthorities {

    /** Issue, list, rotate and revoke your own tokens. */
    public static final String MANAGE_OWN = "pat:manage";

    private PatAuthorities() {
    }
}
