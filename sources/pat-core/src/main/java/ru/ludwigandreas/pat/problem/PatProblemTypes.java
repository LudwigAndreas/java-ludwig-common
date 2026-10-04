package ru.ludwigandreas.pat.problem;

/**
 * The RFC 9457 problem {@code type} URIs for credential failures, and the location a rejected caller is sent.
 *
 * <p>These live in {@code pat-core} rather than in the issuing starter for a reason that is easy to miss: the
 * response they describe is rendered by the <b>edge</b>, not by any module in this repository.
 *
 * <h2>Why the edge renders it</h2>
 *
 * <p>A client holding a token presents it to a service. The edge exchanges it at the issuer and forwards the
 * resulting assertion. So when a token is revoked, expired, mistyped or presented for the wrong audience, the
 * party that learns this is the edge, and the party waiting for an answer is a client that never called the
 * exchange and has no idea it exists.
 *
 * <p>The uniform failure the issuer returns is therefore seen only by the edge. What the client sees is a
 * separate contract, and leaving it unstated is how an automation user ends up with a bare {@code 401} and no
 * way to tell a revoked token from a typo - the difference between a one-minute fix and an afternoon.
 *
 * <h2>The contract</h2>
 *
 * <p>The edge answers {@code 401} with {@code WWW-Authenticate: Bearer error="invalid_token"} and a problem
 * document whose {@code type} is {@link #CREDENTIAL_REJECTED} and whose {@code detail} points at
 * {@link #MANAGEMENT_PATH}. It discloses <b>which</b> cause applied to nobody.
 *
 * <p>Both properties hold at once because of what each audience already knows. <em>That</em> the credential
 * failed is information the legitimate holder needs and an attacker already has - they presented it and got
 * a refusal. <em>Why</em> it failed is information only the attacker gains from: "unknown key id" says which
 * half of the guess to keep working on, and "revoked" confirms that a harvested token was once real and
 * identifies a live target.
 *
 * <h2>Why these are constants and not configuration</h2>
 *
 * <p>So the edge has a definition to configure against rather than one to invent. The same reasoning as
 * {@code PatClaims}: two sides of a seam that will be edited by different people at different times, where a
 * value written twice is a value that diverges. Here the divergence is milder than a privilege escalation - a
 * client library matching on the wrong {@code type} reports the wrong remedy - but it is still a defect
 * nobody would find by testing either side alone.
 *
 * <h2>These are the EDGE's types, not a service's</h2>
 *
 * <p>Worth being exact, because an earlier version of the starter's {@code ProblemDetail} mappers also set
 * these as the {@code type} of the documents <em>it</em> emitted - and produced a body with the key
 * the {@code type} key twice, since {@code web-core} derives its own from the problem code. {@code web-core} owns
 * the type scheme for every document this platform serves; these constants exist for the one document it
 * does not serve, which the edge renders on the issuer's behalf.
 *
 * <p><b>No build in this repository can check that the edge actually returns this.</b> The edge configuration
 * is not here and nothing here can observe it. Publishing the constants and documenting the required response
 * verbatim in the module README is the entire mitigation, and the gap is recorded in the change's enforcement
 * table rather than left for a reader to discover.
 */
public final class PatProblemTypes {

    /** Base URI for this platform's problem types, matching the convention the other modules use. */
    private static final String BASE = "https://problems.ludwigandreas.ru/credential/";

    /**
     * The presented credential was not accepted - for any reason, and the document says which to nobody.
     *
     * <p>Distinct from an authorization denial, and the distinction is the point. The two demand opposite
     * actions from a client: a rejected credential means reissue the token, a missing authority means request
     * a role. A client that cannot tell them apart sends its user down the wrong path, and for an unattended
     * pipeline the wrong path is a silent stall.
     */
    public static final String CREDENTIAL_REJECTED = BASE + "rejected";

    /**
     * The caller is authenticated by a token and this endpoint does not accept one.
     *
     * <p>Returned by the token management surface. {@code 403} and not {@code 401}: the caller's identity is
     * established and it is the <em>credential</em> that is refused, so telling them to re-authenticate would
     * send them round a loop they can complete successfully and still be refused.
     *
     * <p>The rule behind it is that a token may not mint, rotate or revoke a token. Without it a leaked
     * read-only token is a persistence mechanism - exchange it, call the management API, mint a wider one, and
     * revoking the original accomplishes nothing.
     */
    public static final String CREDENTIAL_NOT_PERMITTED_HERE = BASE + "not-permitted-here";

    /** Issuance was refused: a lifetime beyond the ceiling, an empty scope set, an absent audience set. */
    public static final String ISSUANCE_REFUSED = BASE + "issuance-refused";

    /**
     * Where a caller goes to look at, rotate or revoke their tokens.
     *
     * <p>A path rather than an absolute URL, because the host depends on the deployment and this module has no
     * way to know it. The edge and the issuing service each prefix their own.
     */
    public static final String MANAGEMENT_PATH = "/api/v1/personal-access-tokens";

    /** The {@code WWW-Authenticate} value the edge returns alongside {@link #CREDENTIAL_REJECTED}. */
    public static final String WWW_AUTHENTICATE = "Bearer error=\"invalid_token\"";

    private PatProblemTypes() {
    }
}
