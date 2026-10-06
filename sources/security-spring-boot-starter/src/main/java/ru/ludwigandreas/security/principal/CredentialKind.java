package ru.ludwigandreas.security.principal;

/**
 * What the caller presented on this request, as distinct from who they are.
 *
 * <p>Orthogonal to {@link PrincipalType}, and keeping the two apart is the point. {@code PrincipalType}
 * answers <em>which door the caller came through</em> - a browser user, an external partner, a peer service -
 * and has exactly three values because there are exactly three doors. This answers <em>what they held up at
 * that door</em>, which is a different question with a different answer set.
 *
 * <p>A personal access token is deliberately <b>not</b> a fourth {@code PrincipalType}. A token-backed caller
 * is the same person, through the same door, with authority bounded below theirs. Making it a principal type
 * would silently break every {@code isType(USER)} check and every {@code DataScopeProvider} keyed on
 * {@code USER} the moment that person used a token - a fail-open or fail-closed coin toss depending on how
 * each check happens to be written.
 *
 * <p>This dimension exists to answer the two questions {@code PrincipalType} cannot:
 *
 * <ul>
 *   <li><b>For audit</b>: <em>what was presented</em>, alongside <em>who is accountable</em>. A record saying
 *       only "alice did this" loses the fact that it was a CI pipeline holding a token she minted, which is
 *       the first thing an investigation needs.</li>
 *   <li><b>For policy</b>: <em>may this credential reach this endpoint</em>. The token management surface
 *       refuses a token-backed caller, because otherwise a leaked read-only token is a persistence
 *       mechanism - exchange it, mint a wider one, and revoking the original accomplishes nothing.</li>
 * </ul>
 */
public enum CredentialKind {

    /**
     * The caller presented the credential their identity was established with - a session-derived assertion,
     * a client certificate, a workload identity.
     *
     * <p>Every authentication that existed before personal access tokens reports this, which is why the
     * credential dimension is additive rather than a change in meaning.
     */
    DIRECT,

    /**
     * The caller presented a personal access token, which the edge exchanged for the assertion that arrived.
     *
     * <p>The authority on such a request has already been narrowed to the intersection of the owner's live
     * authorities and the token's scopes. Nothing downstream needs to redo that, and nothing downstream
     * should: there is exactly one code path that performs the intersection, enforced by
     * {@code credentials.one-attenuation-path}.
     */
    PERSONAL_ACCESS_TOKEN
}
