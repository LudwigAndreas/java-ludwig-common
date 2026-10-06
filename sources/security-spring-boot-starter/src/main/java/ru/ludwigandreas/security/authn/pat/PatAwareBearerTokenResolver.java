package ru.ludwigandreas.security.authn.pat;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import ru.ludwigandreas.pat.token.PatTokens;

/**
 * Hides an {@code lpat_} credential from the resource server, so that {@link PatAuthenticationFilter} is
 * reached at all.
 *
 * <h2>Why this exists - a position in the chain is not enough</h2>
 *
 * <p>The intent was that the PAT filter run <em>before</em> the bearer-token filter, on the argument the
 * mTLS filter makes: a PAT request carries no bearer JWT, so the resource server would reject it before
 * the token was ever looked at. Two facts discovered by asserting against the registered chain rather
 * than against the configuration make that insufficient:
 *
 * <ol>
 *   <li>Spring Security orders {@code BearerTokenAuthenticationFilter} <b>ahead of</b>
 *       {@code BasicAuthenticationFilter}, so {@code addFilterBefore(..., BasicAuthenticationFilter)} -
 *       the position this module's other filters use - places a filter <em>after</em> the bearer filter,
 *       not before it. The identity-header-stripping filter sits there too, which is why the PAT filter
 *       cannot simply be moved earlier without moving stripping as well.</li>
 *   <li>{@code BearerTokenAuthenticationFilter} does <b>not</b> check for an existing authentication. It
 *       resolves a token, authenticates unconditionally, and on failure commences the entry point and
 *       returns without continuing the chain. So moving the PAT filter in front of it would not have
 *       helped either: the bearer filter would still have fed the {@code lpat_} credential to the JWT
 *       decoder, failed, and answered {@code 401} - overwriting or discarding whatever the PAT filter
 *       had established.</li>
 * </ol>
 *
 * <p>So the mechanism is the resolver, not the ordering: the resource server is told there is no bearer
 * token on this request, which is true - an {@code lpat_} credential is not a JWT and is not addressed
 * to it. The bearer filter then passes the request straight through and the PAT filter, which runs after
 * identity-header stripping as it must, authenticates it.
 *
 * <h2>A second reason, independent of ordering</h2>
 *
 * <p>Without this, a live credential is handed to a JWT decoder on every request. A decode failure is a
 * logged, metered and occasionally stack-traced event, and the token is the input to it; the fewer
 * components a secret reaches, the fewer places it can be printed. Declining to offer it to the decoder
 * is correct even where the ordering happens to work out.
 *
 * <p>Installed <b>only</b> when the PAT filter is enabled, so a deployment on the designed
 * edge-exchange route resolves bearer tokens exactly as Spring Security does by default.
 */
@RequiredArgsConstructor
public class PatAwareBearerTokenResolver implements BearerTokenResolver {

    private static final String PAT_CREDENTIAL_PREFIX = PatTokens.PREFIX + PatTokens.SEPARATOR;

    /**
     * Spring Security's own resolver, used unchanged.
     *
     * <p>Delegated to rather than reimplemented because it owns decisions this class has no opinion on -
     * whether a token may arrive in a form parameter or a query string, and the {@code 400} for a request
     * carrying one in two places at once. Reimplementing it would mean re-deciding those by accident.
     */
    private final BearerTokenResolver delegate;

    @Override
    public String resolve(HttpServletRequest request) {
        String token = delegate.resolve(request);
        return token != null && token.startsWith(PAT_CREDENTIAL_PREFIX) ? null : token;
    }
}
