package ru.ludwigandreas.pat.exchange;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.pat.cache.CachedVerification;
import ru.ludwigandreas.pat.config.PatProperties;
import ru.ludwigandreas.pat.metrics.PatMetrics;
import ru.ludwigandreas.pat.token.PatTokens;

/**
 * The RFC 8693 endpoint the edge calls to exchange a token for a short-lived assertion.
 *
 * <p>This is the one endpoint in the platform where an attacker can test a credential guess, and the whole
 * shape of it follows from that:
 *
 * <ul>
 *   <li><b>Every failure is identical.</b> One status, one problem type, one body, for all eleven causes.
 *       The reason reaches metrics and audit and nothing else. Any distinguishable failure is an oracle.</li>
 *   <li><b>Cheap checks first.</b> Parse, checksum and rate limit before any database access, so a flood of
 *       garbage costs CPU rather than queries.</li>
 *   <li><b>No KDF.</b> The digest comparison is a plain SHA-256, because the secret is 256 bits of
 *       {@code SecureRandom} and a work factor here would add latency to the hot path without protecting
 *       against an attack that can be mounted. See {@code PatDigest}.</li>
 * </ul>
 *
 * <h2>This endpoint does not accept a token as its own caller credential</h2>
 *
 * <p>It <em>consumes</em> a token as the subject being exchanged. It does not treat one as the
 * authentication of the caller making the request, which is the same rule that keeps a token off the
 * management surface: a credential that could operate on credentials makes revoking the original pointless.
 *
 * <p>The endpoint is therefore reached without authentication - the presented token <em>is</em> the
 * credential - and must be published as a public path. That is safe because it grants nothing on its own:
 * it returns an assertion for a token whose secret was proven, and nothing else.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class TokenExchangeController {

    private final PatVerifier verifier;

    private final PatAssertionMinter minter;

    private final ExchangeRateLimiter rateLimiter;

    private final PatProperties properties;

    private final PatMetrics metrics;

    /**
     * Exchanges a token.
     *
     * <p>Form-encoded parameters rather than JSON, because that is what RFC 8693 specifies and what an edge
     * proxy's token-exchange support emits.
     *
     * @param subjectToken the presented token
     * @param resource     the destination service, RFC 8693's {@code resource}
     * @param audience     the same thing under RFC 8693's other name; either is accepted
     */
    @PostMapping(path = "${ludwig.pat.exchange.path:/oauth2/token}",
            consumes = "application/x-www-form-urlencoded")
    public TokenExchangeResponse exchange(
            @RequestParam("grant_type") String grantType,
            @RequestParam("subject_token") String subjectToken,
            @RequestParam(value = "subject_token_type", required = false) String subjectTokenType,
            @RequestParam(value = "resource", required = false) String resource,
            @RequestParam(value = "audience", required = false) String audience,
            HttpServletRequest request) {

        String sourceIp = request.getRemoteAddr();

        // A wrong grant type is the uniform refusal too. It is not a credential failure, but answering it
        // differently would let an attacker confirm that this endpoint exists and what it does without
        // presenting anything.
        if (!TokenExchangeResponse.GRANT_TYPE.equals(grantType)) {
            throw refuse(ExchangeFailure.MALFORMED, null, sourceIp);
        }
        if (subjectTokenType != null && !TokenExchangeResponse.PAT_TOKEN_TYPE.equals(subjectTokenType)) {
            throw refuse(ExchangeFailure.MALFORMED, null, sourceIp);
        }

        String requested = Optional.ofNullable(resource).filter(value -> !value.isBlank())
                .or(() -> Optional.ofNullable(audience).filter(value -> !value.isBlank()))
                .orElseThrow(() -> refuse(ExchangeFailure.AUDIENCE_MISSING, null, sourceIp));

        // Checked against what this issuer will mint for at all, before the token is even parsed. Two
        // gates, not one: this is the deployment's list, the token's own set is checked in the verifier.
        if (!properties.getExchange().getAudiences().isEmpty()
                && !properties.getExchange().getAudiences().contains(requested)) {
            throw refuse(ExchangeFailure.AUDIENCE_NOT_ISSUABLE, null, sourceIp);
        }

        // The key id is needed for the rate limiter and is available without any I/O. Parsed here rather
        // than inside the verifier so a rate-limited request costs one map lookup and no query.
        String keyId = PatTokens.parse(subjectToken).map(PatTokens.ParsedToken::keyId).orElse(null);
        if (!rateLimiter.allows(keyId, sourceIp)) {
            metrics.recordRateLimited(keyId == null ? "source" : "key");
            throw refuse(ExchangeFailure.RATE_LIMITED, keyId, sourceIp);
        }

        CachedVerification verified;
        try {
            verified = verifier.verify(subjectToken, requested, sourceIp);
        } catch (ExchangeRefusedException refused) {
            // Re-thrown rather than handled: the only thing added here is the failure counter and the
            // limiter bump, both of which every refusal needs and neither of which the verifier should
            // know about.
            throw refuse(refused.failure(), keyId, sourceIp);
        }

        rateLimiter.recordSuccess(keyId);

        Duration lifetime = properties.getExchange().getAssertionLifetime();
        String assertion = minter.mint(
                verified.ownerSubject(), verified.patId(), verified.scopes(), requested, lifetime);

        metrics.recordExchange(requested);
        return TokenExchangeResponse.of(assertion, lifetime.toSeconds());
    }

    /**
     * Counts, limits and logs a refusal, then produces the exception that renders as the uniform response.
     *
     * <p>One place, so that no refusal path can forget the counter or the limiter bump. The log line carries
     * the reason; the response carries none.
     */
    private ExchangeRefusedException refuse(ExchangeFailure failure, String keyId, String sourceIp) {
        metrics.recordExchangeFailure(failure.tag());
        rateLimiter.recordFailure(keyId, sourceIp);
        log.debug("Token exchange refused from {}: {}", sourceIp, failure.tag());
        return new ExchangeRefusedException(failure);
    }
}
