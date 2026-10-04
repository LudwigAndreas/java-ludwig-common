package ru.ludwigandreas.security.config;

import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;

/**
 * Computes how long a revoked personal access token can still work, logs it, and refuses to start when it is
 * too long.
 *
 * <p>Three TTLs compose into the one number that matters and nobody multiplies them out in production. The
 * failure mode is a revoked credential that keeps working for an unbounded time while every test passes and
 * every dashboard is green - which is the same category of defect as a skipped audience check, and is handled
 * the same way here: by refusing to start rather than by warning.
 *
 * <p><b>Two compositions, both computed.</b> A request/response caller's window is bounded by the assertion's
 * lifetime plus how long the edge reuses it plus the authority cache's TTL. A caller holding a long-lived
 * connection has a different window entirely - the connection was authenticated once, so what bounds it is
 * how often the service re-derives authority, plus the same cache TTL. Computing only the first would log a
 * correct-looking number while being false for exactly the callers whose window is largest, which is worse
 * than computing neither.
 *
 * <p>The authority cache TTL is read from the cache module rather than duplicated here, because it is already
 * declared there as a {@code CachePurpose.SECURITY} setting with its own startup ceiling. This validator adds
 * the terms that cache cannot see: the issuer's assertion lifetime and the edge's reuse window, neither of
 * which is in this repository.
 */
@Slf4j
public class RevocationWindowValidator {

    private final SecurityProperties.Pat pat;

    private final Duration authorityCacheTtl;

    public RevocationWindowValidator(SecurityProperties.Pat pat, Duration authorityCacheTtl) {
        this.pat = pat;
        this.authorityCacheTtl = authorityCacheTtl == null ? Duration.ZERO : authorityCacheTtl;
    }

    /**
     * Resolves the authority cache's configured TTL, or zero when the cache module is not in use.
     *
     * <p>Read from the resolver rather than from {@code AuthorityCaches.definition()}'s default, because the
     * default is only what this module suggests - a deployment overrides it under
     * {@code ludwig.cache.caches.authorities.ttl}, and summing the suggestion rather than the override would
     * make the computed window wrong on exactly the deployments that had thought about it.
     */
    public static Duration authorityCacheTtl(
            ru.ludwigandreas.cache.config.CacheSettingsResolver resolver) {
        if (resolver == null) {
            return Duration.ZERO;
        }
        return resolver.resolve(ru.ludwigandreas.security.authz.AuthorityCaches.definition()).ttl();
    }

    /**
     * Computes both windows, logs them, and throws when either exceeds the ceiling.
     *
     * <p>Logged even when it passes, and that is deliberate: the number then exists in the record of every
     * deployment rather than only in the head of whoever last reasoned about it. An operator asked "how long
     * does revocation take here?" during an incident should be able to grep for the answer.
     */
    public void validate() {
        if (!pat.isValidateRevocationWindow()) {
            log.warn("ludwig.security.pat.validate-revocation-window=false - the time a revoked personal"
                    + " access token keeps working is not being computed or bounded. The failure mode this"
                    + " check exists for cannot be found by testing, because everything works.");
            return;
        }

        Duration requestResponse = pat.getAssertionLifetime()
                .plus(pat.getEdgeCacheLifetime())
                .plus(authorityCacheTtl);
        Duration longLived = pat.getRevalidationInterval().plus(authorityCacheTtl);
        Duration ceiling = pat.getMaxRevocationWindow();

        if (requestResponse.compareTo(ceiling) > 0) {
            throw exceeded("request/response", requestResponse, ceiling, List.of(
                    "assertion-lifetime=" + pat.getAssertionLifetime(),
                    "edge-cache-lifetime=" + pat.getEdgeCacheLifetime(),
                    "authority cache ttl=" + authorityCacheTtl));
        }
        if (longLived.compareTo(ceiling) > 0) {
            throw exceeded("long-lived connection", longLived, ceiling, List.of(
                    "revalidation-interval=" + pat.getRevalidationInterval(),
                    "authority cache ttl=" + authorityCacheTtl));
        }

        log.info("Personal access token revocation window: {} for request/response callers"
                        + " (assertion {} + edge cache {} + authority cache {}), {} for long-lived"
                        + " connections (revalidation {} + authority cache {}). Ceiling {}.",
                requestResponse, pat.getAssertionLifetime(), pat.getEdgeCacheLifetime(), authorityCacheTtl,
                longLived, pat.getRevalidationInterval(), authorityCacheTtl, ceiling);
    }

    private SecurityConfigurationException exceeded(String path, Duration actual, Duration ceiling,
                                                     List<String> terms) {
        return new SecurityConfigurationException(
                "The " + path + " revocation window is " + actual + ", which exceeds"
                        + " ludwig.security.pat.max-revocation-window=" + ceiling + ". That is how long a"
                        + " revoked personal access token would keep working. Contributing values: "
                        + String.join(", ", terms) + ". Lower one of them, or raise the ceiling"
                        + " deliberately - but a window nobody has computed is one nobody can bound, and"
                        + " this is not a defect that surfaces on its own: a revoked token that still works"
                        + " looks exactly like a token that works.");
    }
}
