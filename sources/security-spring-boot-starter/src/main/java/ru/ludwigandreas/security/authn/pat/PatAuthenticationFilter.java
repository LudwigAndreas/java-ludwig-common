package ru.ludwigandreas.security.authn.pat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.ludwigandreas.cache.api.LudwigCache;
import ru.ludwigandreas.pat.introspection.PatIntrospectionResponse;
import ru.ludwigandreas.pat.scope.PatAttenuation;
import ru.ludwigandreas.pat.token.PatTokens;
import ru.ludwigandreas.security.authn.attenuation.AttenuatedAuthentications;
import ru.ludwigandreas.security.authz.Authorities;
import ru.ludwigandreas.security.authz.AuthorityLookup;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.security.metrics.SecurityMetrics;
import ru.ludwigandreas.security.principal.PrincipalType;

/**
 * Authenticates a personal access token presented directly, by introspecting it at the issuing service.
 *
 * <h2>Why this exists, and that it is meant to be deleted</h2>
 *
 * <p>The designed route is that the edge exchanges an {@code lpat_} credential for a short-lived
 * assertion, and {@code JwtPrincipalConverter} reads the {@code ludwig_pat} claim off it. That requires an
 * edge willing to perform the exchange. Where the edge is a company-provided session gateway that does
 * cookie-to-JWT only, cannot be extended, and redirects a request with no session to OIDC, a personal
 * access token cannot be used at all.
 *
 * <p>So this filter is scaffolding. It is <b>off unless</b>
 * {@code ludwig.security.pat.filter.enabled} is set, and when the edge gains PAT support it is turned
 * off and the claim reader - already shipped and tested - carries the traffic. Nothing else changes: the
 * table, the management API, the attenuation and the audit trail are the same in both worlds.
 *
 * <h2>Its position in the chain, and the justification the chain's javadoc demands</h2>
 *
 * <p>Immediately after the identity-header-stripping filter, so nothing it reads can be a
 * client-supplied identity header. The chain documents four filters and says a fifth must justify its
 * place; that is the justification.
 *
 * <p><b>Not before the bearer-token filter</b>, which is what this was first written to claim, by
 * analogy with the mTLS filter. The analogy does not hold, and the difference is worth stating because
 * it is the kind of thing that reads as correct indefinitely: an mTLS request carries no
 * {@code Authorization} header at all, so the resource server's token resolver finds nothing and passes
 * it through, whereas a PAT request carries one and the resource server would try to decode it.
 * {@link PatAwareBearerTokenResolver} is what makes this path reachable - it tells the resource server
 * there is no bearer token here, which is true. Its javadoc records the two chain facts that rule the
 * ordering fix out, and {@code SecurityAutoConfigurationTest} asserts the real registered order so that
 * this paragraph cannot quietly stop being true.
 *
 * <h2>It computes no authority</h2>
 *
 * <p>The intersection and the principal construction happen in {@link AttenuatedAuthentications}, which is
 * the single site {@code credentials.one-attenuation-path} fences. This filter supplies facts: the owner
 * from the introspection, that owner's authorities resolved <b>live</b> through the same
 * {@link AuthorityLookup} every other path uses, and the token's scopes. When this filter was added the
 * rule was <em>narrowed</em> to name one type rather than grow a second package - see that type.
 *
 * <h2>What it does not do</h2>
 *
 * <p>A request that already carries an authentication passes through untouched, for the reason the mTLS
 * filter does the same: a filter that overwrote an established identity would make the order of the chain
 * a security decision rather than plumbing.
 *
 * <p>A credential without the {@code lpat_} prefix passes through untouched, so enabling this path cannot
 * change how an ordinary company-issued JWT is handled.
 */
@Slf4j
@RequiredArgsConstructor
public class PatAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final PatIntrospectionClient client;

    private final LudwigCache<String, PatIntrospectionResponse> cache;

    private final AuthorityLookup authorityLookup;

    private final SecurityMetrics metrics;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String presented = presentedToken(request);
        if (presented == null || SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(request, response);
            return;
        }

        authenticate(presented);
        chain.doFilter(request, response);
    }

    /**
     * Introspects and, on success, establishes the authentication.
     *
     * <p>A failure sets nothing and does not reject the request here. The chain continues, nothing else
     * authenticates it, and the authorization rules refuse it with this module's own localized {@code 401}
     * - which is the same shape every other unauthenticated request takes. Rejecting inside the filter
     * would mean this module emitting a second kind of 401 that differs from the one the entry point
     * produces, for no benefit to the caller.
     */
    private void authenticate(String presented) {
        Optional<PatIntrospectionResponse> introspected = verify(presented);
        if (introspected.isEmpty()) {
            metrics.recordAuthenticationFailed(PrincipalType.USER.name(), "pat-inactive");
            return;
        }
        PatIntrospectionResponse token = introspected.get();

        // Resolved live, through the same lookup, with the same cache and metrics as every other path.
        // This is what makes a revoked role stop working for a token within the authority cache's TTL.
        Authorities authorities = authorityLookup.lookup(
                new PrincipalRef(PrincipalType.USER, token.sub()));

        SecurityContextHolder.getContext().setAuthentication(
                AttenuatedAuthentications.attenuated(
                        token.sub(),
                        PrincipalType.USER,
                        token.sub(),
                        authorities,
                        PatAttenuation.of(token.scopes()),
                        token.patId()));

        metrics.recordAuthenticated(PrincipalType.USER.name());
    }

    /**
     * The cached introspection, keyed on the presented secret's digest.
     *
     * <p>Keyed on the digest rather than the secret, because a cache key reaches metrics, a shared tier's
     * key space and a heap dump. The key <em>is</em> the proof of possession - a hit cannot be obtained
     * without presenting the secret - so caching on it is sound as well as safe.
     *
     * <p>Parsed first, so a malformed credential costs no network call at all. That ordering is the same
     * reason the issuer parses before it reads the database: the one endpoint an attacker can aim at
     * should answer garbage from CPU rather than from I/O.
     *
     * <p>The digest comes from {@code PatTokens.parse} rather than being computed here, so the hashing
     * choice has exactly one implementation shared with the issuer. A second call site for it would be a
     * second place the algorithm could drift, which is what Checkstyle's {@code WeakDigestAlgorithm} rule
     * exists to catch and what one implementation makes unnecessary.
     */
    private Optional<PatIntrospectionResponse> verify(String presented) {
        Optional<PatTokens.ParsedToken> parsed = PatTokens.parse(presented);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        String key = parsed.get().digest();
        return cache.get(key).or(() -> {
            Optional<PatIntrospectionResponse> fresh = client.introspect(presented);
            fresh.ifPresent(value -> cache.put(key, value));
            return fresh;
        });
    }

    /** The {@code lpat_}-prefixed bearer credential, or {@code null} when this request carries none. */
    private String presentedToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            return null;
        }
        String credential = header.substring(BEARER.length()).trim();
        return credential.startsWith(PatTokens.PREFIX + PatTokens.SEPARATOR) ? credential : null;
    }

}
