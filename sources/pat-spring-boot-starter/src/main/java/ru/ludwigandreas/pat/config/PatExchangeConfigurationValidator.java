package ru.ludwigandreas.pat.config;

import lombok.extern.slf4j.Slf4j;

/**
 * Refuses to start an exchange endpoint that cannot mint a valid assertion.
 *
 * <p>Modelled on {@code security-spring-boot-starter}'s audience check, whose javadoc makes the argument
 * this class reuses: a misconfiguration that produces tokens nobody can validate is not a defect that
 * surfaces on its own, so it is worth refusing to start over rather than warning about.
 */
@Slf4j
public class PatExchangeConfigurationValidator {

    /** Length of Spring's recursive wildcard suffix, so the prefix comparison below is not a literal. */
    private static final int WILDCARD_SUFFIX_LENGTH = "/**".length();

    private final PatProperties.Exchange exchange;

    private final java.util.List<String> publicPaths;

    public PatExchangeConfigurationValidator(PatProperties.Exchange exchange,
                                              java.util.List<String> publicPaths) {
        this.exchange = exchange;
        this.publicPaths = publicPaths == null ? java.util.List.of() : publicPaths;
    }

    /** Fails on a missing issuer; warns on an empty audience list. */
    public void validate() {
        if (exchange.getIssuer() == null || exchange.getIssuer().isBlank()) {
            throw new IllegalStateException(
                    "ludwig.pat.exchange.enabled is true but ludwig.pat.exchange.issuer is not set. An"
                            + " assertion minted with no issuer is one no resource server can validate the"
                            + " provenance of, and every service would reject it - which presents as"
                            + " 'personal access tokens do not work' rather than as a missing property."
                            + " Set it to this issuer's identifier, the same value the services'"
                            + " spring.security.oauth2.resourceserver.jwt.issuer-uri names.");
        }
        if (!isReachableWithoutAuthentication()) {
            // A warning rather than a failure, because the failure mode is loud and safe: the edge gets a
            // 401 from the exchange, no assertion is minted, and personal access tokens visibly do not
            // work. Nothing is exposed. Refusing to start would be defensible, but this module cannot
            // reliably tell whether an application has taken over the filter chain and published the path
            // its own way - and failing startup over a path pattern this module cannot see would be worse
            // than a warning that is occasionally wrong.
            log.warn("The token exchange is mounted at {} but that path is not in"
                            + " ludwig.security.public-paths ({}). The exchange CONSUMES a personal access"
                            + " token as its subject - it does not accept one as the caller's credential -"
                            + " so it has to be reachable unauthenticated, or the edge will receive a 401"
                            + " and no token will ever be exchanged. Add it, unless this application"
                            + " publishes the path through its own SecurityFilterChain.",
                    exchange.getPath(), publicPaths);
        }
        if (exchange.getAudiences().isEmpty()) {
            // A warning rather than a failure: an empty list means "mint for any audience the token itself
            // permits", which is coherent and reasonably safe because the token's own audience set still
            // gates it. Warned about because a deployment that meant to restrict and left it empty has no
            // other signal that it did not.
            log.warn("ludwig.pat.exchange.audiences is empty, so this issuer will mint an assertion for"
                    + " any audience a presented token permits. The token's own audience set still"
                    + " applies. If you meant to restrict which services this issuer mints for at all,"
                    + " list them.");
        }
    }

    /**
     * Whether the configured public paths plausibly cover the exchange path.
     *
     * <p>A prefix comparison rather than Spring's own path matching, because the point is to catch the
     * deployment that simply forgot - and an exact or wildcard match covers that. A pattern this misses is
     * a false warning, which is the harmless direction for a check whose subject is a configuration list
     * this module does not own.
     */
    private boolean isReachableWithoutAuthentication() {
        String path = exchange.getPath();
        return publicPaths.stream().anyMatch(candidate -> {
            String trimmed = candidate.trim();
            if (trimmed.endsWith("/**")) {
                return path.startsWith(trimmed.substring(0, trimmed.length() - WILDCARD_SUFFIX_LENGTH));
            }
            return trimmed.equals(path);
        });
    }
}
