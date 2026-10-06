package ru.ludwigandreas.pat.exchange;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.pat.cache.CachedVerification;
import ru.ludwigandreas.pat.introspection.PatIntrospectionResponse;
import ru.ludwigandreas.pat.metrics.PatMetrics;
import ru.ludwigandreas.pat.token.PatTokens;

/**
 * Answers a service's question: is this token live, and whose is it?
 *
 * <h2>Why this exists alongside the exchange</h2>
 *
 * <p>The exchange endpoint assumes an edge that will call it and replace the caller's credential with the
 * resulting assertion. This platform's edge is a company-provided session gateway that does
 * session-cookie-to-JWT and nothing else, cannot be extended, and redirects a request with no session to
 * OIDC - so under the exchange-only design a personal access token cannot be used at all.
 *
 * <p>This endpoint lets a <em>service</em> authenticate the credential itself. It is scaffolding: when the
 * company gateway gains PAT support, the exchange is what it will call, and this and the filter that uses
 * it are removed.
 *
 * <h2>Why it reuses PatVerifier rather than reimplementing verification</h2>
 *
 * <p>Because every property that matters is already in there and would otherwise be rebuilt slightly
 * differently: the parse before any I/O, the checksum rejection before any database access, the indexed
 * point read on either key id, the constant-time digest comparison, the revocation and expiry checks, the
 * CIDR allowlist, the rotation overlap, and the {@code CachePurpose.SECURITY} cache whose TTL is the
 * revocation window. A second verification path would be a second place for one of those to be subtly
 * wrong, which is the shape of defect this module is built to avoid.
 *
 * <p>The one thing this endpoint does differently is what it <b>returns</b>: facts rather than a signed
 * assertion. That is the whole simplification of this route - no signing key, no JWKS, no second issuer.
 *
 * <h2>Every failure is identical</h2>
 *
 * <p>{@link PatIntrospectionResponse#inactive()} for all of them, and {@code 200} rather than an error
 * status - RFC 7662's model, where "this token is not usable" is a successful answer to a valid question.
 * The reason reaches {@link PatMetrics} and the audit sink and nothing else, for the same reason the
 * exchange's uniform failure exists: anything that distinguishes an unknown key id from a bad secret
 * tells an attacker which half of their guess to keep working on.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PatIntrospectionController {

    private final PatVerifier verifier;

    private final PatMetrics metrics;

    private final ExchangeRateLimiter rateLimiter;

    /**
     * Introspects a token for the calling service.
     *
     * <p>The {@code resource} parameter is the <b>calling service's own audience</b>, not a destination:
     * the caller is asking "may this token be used *here*". Required, for the reason the exchange requires
     * it - every available default is either "every service this token permits" or "everything", and both
     * are the condition audience binding exists to prevent.
     */
    @PostMapping(path = "${ludwig.pat.introspection.path:/introspect}",
            consumes = "application/x-www-form-urlencoded")
    public PatIntrospectionResponse introspect(
            @RequestParam("token") String token,
            @RequestParam(value = "resource", required = false) String resource,
            HttpServletRequest request) {

        String sourceIp = request.getRemoteAddr();

        if (resource == null || resource.isBlank()) {
            return refuse(ExchangeFailure.AUDIENCE_MISSING, null, sourceIp);
        }

        // Parsed here for the rate limiter's key, without any I/O - so a refused key id costs one map
        // lookup and no query, exactly as on the exchange path.
        String keyId = PatTokens.parse(token).map(PatTokens.ParsedToken::keyId).orElse(null);
        if (!rateLimiter.allows(keyId, sourceIp)) {
            metrics.recordRateLimited(keyId == null ? "source" : "key");
            return refuse(ExchangeFailure.RATE_LIMITED, keyId, sourceIp);
        }

        CachedVerification verified;
        try {
            verified = verifier.verify(token, resource, sourceIp);
        } catch (ExchangeRefusedException refused) {
            return refuse(refused.failure(), keyId, sourceIp);
        }

        rateLimiter.recordSuccess(keyId);
        return PatIntrospectionResponse.active(
                verified.ownerSubject(), verified.scopes(), verified.audiences(),
                verified.patId(), verified.expiresAt());
    }

    /**
     * Counts, limits and logs a refusal, then returns the one inactive response.
     *
     * <p>Returns rather than throws, which is the difference from the exchange's {@code refuse}. RFC 7662
     * says an unusable token is a {@code 200} with {@code active: false} - the question was valid and has
     * an answer. Throwing would route this through the {@code ProblemDetail} pipeline and produce a
     * {@code 401}, which a client would read as "your introspection call was unauthorized" rather than
     * "the token you asked about is not usable".
     */
    private PatIntrospectionResponse refuse(ExchangeFailure failure, String keyId, String sourceIp) {
        metrics.recordExchangeFailure(failure.tag());
        rateLimiter.recordFailure(keyId, sourceIp);
        log.debug("Introspection reported inactive to {}: {}", sourceIp, failure.tag());
        return PatIntrospectionResponse.inactive();
    }
}
