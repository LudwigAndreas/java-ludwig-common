package ru.ludwigandreas.pat.exchange;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The RFC 8693 token-exchange response.
 *
 * <p>Field names are the RFC's, snake_cased, because the consumer is an edge proxy configured against the
 * standard rather than against this module.
 *
 * <p><b>{@code expiresIn} is the load-bearing field and it is why this type exists rather than returning the
 * assertion as a string.</b> It is how the response <em>declares its own cacheable lifetime</em>, and that
 * declaration is what makes the whole topology highly available: the edge caches per
 * {@code (secret digest, audience)} for this long, so exchanges stay proportional to distinct tokens per
 * lifetime rather than to request volume. Without it the edge has to invent a number, and the safe number
 * to invent is zero - at which point every service request is an issuer request by proxy and the design
 * collapses into the remote-check-per-request alternative that was rejected for exactly that reason.
 *
 * <p>No build in this repository can verify that the edge honours it. The mitigation is the metric
 * {@code ludwig.pat.exchange}, on which a non-caching edge shows up as traffic rising with request volume
 * instead of staying flat - recorded in the change's enforcement table as one of the conventions no build
 * can hold.
 *
 * @param accessToken     the minted assertion
 * @param issuedTokenType the RFC 8693 identifier for what was issued
 * @param tokenType       always {@code Bearer}
 * @param expiresIn       seconds the assertion is valid, and the lifetime the edge may cache it for
 */
public record TokenExchangeResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("issued_token_type") String issuedTokenType,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn) {

    /** RFC 8693's identifier for a JWT access token. */
    public static final String ISSUED_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:jwt";

    /** This platform's identifier for a personal access token, used as the subject token type. */
    public static final String PAT_TOKEN_TYPE = "urn:ludwig:params:oauth:token-type:pat";

    /** The grant type the endpoint accepts. */
    public static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:token-exchange";

    public static TokenExchangeResponse of(String assertion, long expiresInSeconds) {
        return new TokenExchangeResponse(assertion, ISSUED_TOKEN_TYPE, "Bearer", expiresInSeconds);
    }

    /**
     * Masked.
     *
     * <p>The assertion is a bearer credential for its lifetime. Shorter-lived than the token it came from,
     * and still not something to put in a log line - which a record's generated {@code toString()} would do
     * from any interceptor or debugger that printed the controller's return value.
     */
    @Override
    public String toString() {
        return "TokenExchangeResponse[access_token=not shown, expires_in=" + expiresIn + "]";
    }
}
