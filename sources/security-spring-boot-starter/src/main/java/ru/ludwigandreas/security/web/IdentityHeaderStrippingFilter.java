package ru.ludwigandreas.security.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.ludwigandreas.security.authn.mtls.TrustedProxies;

/**
 * Removes client-supplied identity headers before anything downstream can read them.
 *
 * <p>The edge is supposed to strip these on the way in, and usually does. This filter exists because
 * "usually" is not a security property: one misconfigured route, one new ingress, one direct-to-pod
 * debugging path, and a header a client set for itself is read as an assertion the infrastructure
 * made. Stripping again here costs a map lookup per request and removes a whole class of privilege
 * escalation that is otherwise invisible until someone tries it.
 *
 * <p>It is a wrapper rather than a mutation because the servlet API has no way to remove a header from
 * a request. Requests from a trusted proxy pass through untouched - that peer is exactly the one
 * entitled to assert them.
 *
 * <p>Which headers it strips is decided by the caller, and one exclusion matters: when mTLS
 * authentication is enabled, {@code x-forwarded-client-cert} is deliberately left in place here so that
 * {@link ru.ludwigandreas.security.authn.mtls.MutualTlsAuthenticationFilter} - which runs next - can see
 * a forged one and <em>reject</em> the request. Stripping it first would turn a spoofing attempt into an
 * ordinary unauthenticated request: the same 401 either way, but no warning, no metric, and nothing in
 * the logs to tell an operator that someone is reaching the service directly and trying to impersonate a
 * partner. See {@code ResourceServerAutoConfiguration}, which builds the list.
 */
@Slf4j
public class IdentityHeaderStrippingFilter extends OncePerRequestFilter {

    private final Set<String> forbidden;
    private final TrustedProxies trustedProxies;

    public IdentityHeaderStrippingFilter(List<String> forbiddenHeaders, TrustedProxies trustedProxies) {
        this.forbidden = forbiddenHeaders.stream()
                .map(header -> header.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.trustedProxies = trustedProxies;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (trustedProxies != null && trustedProxies.isTrusted(request)) {
            chain.doFilter(request, response);
            return;
        }
        chain.doFilter(new StrippedRequest(request), response);
    }

    private final class StrippedRequest extends HttpServletRequestWrapper {

        private StrippedRequest(HttpServletRequest request) {
            super(request);
        }

        private boolean isForbidden(String name) {
            return name != null && forbidden.contains(name.toLowerCase(Locale.ROOT));
        }

        @Override
        public String getHeader(String name) {
            return isForbidden(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return isForbidden(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            return Collections.enumeration(Collections.list(super.getHeaderNames()).stream()
                    .filter(name -> !isForbidden(name))
                    .toList());
        }
    }
}
