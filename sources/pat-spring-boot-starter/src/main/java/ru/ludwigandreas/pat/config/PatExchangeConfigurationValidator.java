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

    /**
     * The introspection path, or {@code null} when that endpoint is not mounted.
     *
     * <p>Nullable rather than an {@code Optional} field because this is a configuration holder read once at
     * startup, and the module's other validators do the same with an absent path.
     */
    private final String introspectionPath;

    public PatExchangeConfigurationValidator(PatProperties.Exchange exchange,
                                              java.util.List<String> publicPaths) {
        this(exchange, publicPaths, null);
    }

    public PatExchangeConfigurationValidator(PatProperties.Exchange exchange,
                                              java.util.List<String> publicPaths,
                                              String introspectionPath) {
        this.exchange = exchange;
        this.publicPaths = publicPaths == null ? java.util.List.of() : publicPaths;
        this.introspectionPath = introspectionPath;
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
        if (!isReachableWithoutAuthentication(exchange.getPath())) {
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
        warnIfIntrospectionIsUnreachable();
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
     * Warns when the introspection endpoint is mounted but no caller can reach it.
     *
     * <p>The same failure as the exchange's and found the same way - by an integration test that drove the
     * whole loop rather than each half. A service authenticating a token itself calls this endpoint with
     * <b>no credential of its own</b>: the only credential in the exchange is the token under
     * examination, and that is the subject of the question, not the caller's identity. Behind
     * {@code anyRequest().authenticated()} the call gets a {@code 401}, introspection fails closed, and
     * every personal access token is refused - which presents as "the direct path does not work".
     *
     * <p><b>Why public is the right answer here and not a weakening.</b> This platform's exchange endpoint
     * is already public on exactly this reasoning, and the two must not disagree: a caller who could learn
     * something from introspection can learn strictly more from the exchange, which takes the same
     * credential, performs the same verification and hands back a usable assertion. Both require
     * presenting the full secret, so neither is an enumeration oracle - the thing an attacker lacks is the
     * token, and nothing here helps them get one. The rate limiter applies to both.
     *
     * <p>What "public" does not mean is unprotected. The endpoint is expected to be reachable only inside
     * the mesh, which is a deployment control this module cannot see or assert. And
     * {@code PatCredentialGuard} still runs: a request that <em>is</em> authenticated by a personal access
     * token is refused, because a credential that can operate on credentials makes revoking the original
     * pointless.
     *
     * <p>A warning rather than a failure, for the reason the exchange's check gives: this module cannot
     * tell whether an application has taken over the filter chain and published the path its own way, and
     * failing startup over a path pattern it cannot see would be worse than a warning that is occasionally
     * wrong.
     */
    private void warnIfIntrospectionIsUnreachable() {
        if (introspectionPath == null || introspectionPath.isBlank()
                || isReachableWithoutAuthentication(introspectionPath)) {
            return;
        }
        log.warn("The introspection endpoint is mounted at {} but that path is not in"
                        + " ludwig.security.public-paths ({}). A service authenticating a personal access"
                        + " token itself calls this endpoint with no credential of its own - the only"
                        + " credential involved is the token being asked about - so behind"
                        + " anyRequest().authenticated() the call receives a 401, introspection fails"
                        + " closed, and every token is refused. Add it, unless this application publishes"
                        + " the path through its own SecurityFilterChain. It is no more disclosing than"
                        + " the exchange, which is already public: both require the full secret, and the"
                        + " exchange returns strictly more.",
                introspectionPath, publicPaths);
    }

    /**
     * Whether the configured public paths plausibly cover a path.
     *
     * <p>A prefix comparison rather than Spring's own path matching, because the point is to catch the
     * deployment that simply forgot - and an exact or wildcard match covers that. A pattern this misses is
     * a false warning, which is the harmless direction for a check whose subject is a configuration list
     * this module does not own.
     */
    private boolean isReachableWithoutAuthentication(String path) {
        return publicPaths.stream().anyMatch(candidate -> {
            String trimmed = candidate.trim();
            if (trimmed.endsWith("/**")) {
                return path.startsWith(trimmed.substring(0, trimmed.length() - WILDCARD_SUFFIX_LENGTH));
            }
            return trimmed.equals(path);
        });
    }
}
