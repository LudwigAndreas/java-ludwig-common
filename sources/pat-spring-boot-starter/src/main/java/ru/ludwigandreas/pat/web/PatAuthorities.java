package ru.ludwigandreas.pat.web;

/**
 * The two authorities the management API distinguishes.
 *
 * <p>Two rather than one, because minting a credential for yourself and minting one for somebody else are
 * different acts with different blast radiuses. Self-service is the ordinary case and should be ordinary to
 * grant; issuing on behalf of another subject is how a long-lived automation credential outlives the person
 * who set it up, and it is also how somebody could mint a credential that acts as a colleague.
 *
 * <p>Permissions rather than roles, so a deployment can attach them to whatever role structure it already
 * has. {@code LudwigPrincipal} keeps roles and permissions separate for exactly this - a fine-grained
 * entitlement that does not deserve a role of its own.
 */
public final class PatAuthorities {

    /** Issue, list, rotate and revoke your own tokens. */
    public static final String MANAGE_OWN = "pat:manage";

    /**
     * Do the same for another subject.
     *
     * <p>Deliberately not implied by {@link #MANAGE_OWN}. A caller holding only the first and naming
     * somebody else is refused, which is the check that keeps "I can manage tokens" from meaning "I can
     * manufacture a credential that acts as any colleague".
     */
    public static final String MANAGE_ANY = "pat:manage:any";

    private PatAuthorities() {
    }
}
