package ru.ludwigandreas.pat.exchange;

/**
 * Why an exchange failed - for metrics and audit, and for nothing else.
 *
 * <p>This enum exists precisely so that the reason has somewhere to live that is <b>not</b> the HTTP
 * response. Every value here produces one byte-for-byte identical refusal to the caller.
 *
 * <p>Any distinguishable failure on this endpoint is an oracle. "Unknown key id" tells an attacker which
 * half of their guess to keep working on; "revoked" confirms that a harvested token was once real and names
 * a live target; "wrong audience" maps out which services a token reaches. The defender needs all three and
 * loses nothing by reading them in a metric tag, because the defender has the metrics and the attacker has
 * the response body.
 */
public enum ExchangeFailure {

    /**
     * The presented value is not a well-formed token. Rejected before any database access.
     *
     * <p>This covers a failed checksum too, and that merge is deliberate rather than lazy. There was a
     * separate {@code BAD_CHECKSUM} value here and it was <b>unreachable</b>: {@code PatTokens.parse}
     * returns an empty {@code Optional} for a bad prefix, a wrong arity, an empty part and a failed
     * checksum alike, so nothing could ever produce it. {@code UniformExchangeFailureIT} is what found
     * that - an enum constant no code path can reach is dead code that reads as coverage in a metrics
     * dashboard, which is worse than its absence.
     *
     * <p>Distinguishing them would mean a parse that reports <em>why</em>, which is a reasonable future
     * change for the defender's benefit - "many truncated tokens" is a client bug and "random garbage" is a
     * scanner - but it is a change to the parser, not a constant in this enum.
     */
    MALFORMED("malformed"),

    /** No token has that key id. */
    UNKNOWN_KEY("unknown-key"),

    /** The key id exists but the secret's digest does not match. */
    BAD_SECRET("bad-secret"),

    /**
     * The token was revoked.
     *
     * <p>The one value here that should <b>alert</b> rather than merely be counted: presenting a revoked
     * credential means something still holds it.
     */
    REVOKED("revoked"),

    /** The token's expiry has passed. */
    EXPIRED("expired"),

    /** The request came from outside the token's CIDR allowlist. */
    SOURCE_NOT_ALLOWED("source-not-allowed"),

    /** The requested audience is not one this token permits. */
    AUDIENCE_NOT_PERMITTED("audience-not-permitted"),

    /** The requested audience is not one this issuer mints for at all. */
    AUDIENCE_NOT_ISSUABLE("audience-not-issuable"),

    /** No audience was requested. There is deliberately no default. */
    AUDIENCE_MISSING("audience-missing"),

    /** The rate limiter refused this key id or this source. */
    RATE_LIMITED("rate-limited");

    private final String tag;

    ExchangeFailure(String tag) {
        this.tag = tag;
    }

    /** The low-cardinality metric tag. Fixed set, never fed from request data. */
    public String tag() {
        return tag;
    }
}
