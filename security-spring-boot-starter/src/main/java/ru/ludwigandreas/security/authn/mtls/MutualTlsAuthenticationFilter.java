package ru.ludwigandreas.security.authn.mtls;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.ludwigandreas.security.authz.Authorities;
import ru.ludwigandreas.security.authz.AuthorityLookup;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.security.metrics.SecurityMetrics;
import ru.ludwigandreas.security.principal.LudwigAuthentication;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;

/**
 * Authenticates external partners and peer services from their client certificate.
 *
 * <p>Two sources, in order of preference:
 *
 * <ol>
 *   <li><b>Envoy's {@code x-forwarded-client-cert}</b> - the normal path. Envoy terminated TLS,
 *       validated the chain against the partner trust bundle, and described the result in the header.
 *       The header is honored only when the request came from a {@link TrustedProxies trusted peer};
 *       from anywhere else it is treated as a forgery attempt and the request is rejected outright
 *       rather than quietly downgraded, because a spoofed identity header is a security event and
 *       should show up as one.</li>
 *   <li><b>The servlet container's own {@code X509Certificate} attribute</b> - for deployments where
 *       the service terminates client TLS itself, with no proxy in front.</li>
 * </ol>
 *
 * <p>A request carrying neither passes through untouched: the JWT filter downstream handles browser
 * users, and the authorization rules decide what an unauthenticated request may reach. This filter
 * never grants anything by itself - it only establishes who is calling.
 */
@Slf4j
@RequiredArgsConstructor
public class MutualTlsAuthenticationFilter extends OncePerRequestFilter {

    public static final String XFCC_HEADER = "x-forwarded-client-cert";
    private static final String X509_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";
    private static final int SAN_TYPE_DNS = 2;
    private static final int SAN_TYPE_URI = 6;

    private final PartnerIdentityResolver identityResolver;
    private final AuthorityLookup authorityLookup;
    private final TrustedProxies trustedProxies;
    private final AuthenticationEntryPoint entryPoint;
    private final SecurityMetrics metrics;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing != null && existing.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }

        String xfcc = request.getHeader(XFCC_HEADER);
        if (xfcc != null && !trustedProxies.isTrusted(request)) {
            log.warn("Rejecting {} from untrusted peer {}: only the mesh proxy may assert client "
                    + "certificates", XFCC_HEADER, TrustedProxies.peerAddressOf(request));
            metrics.recordAuthenticationFailed(PrincipalType.PARTNER.name(), "untrusted-proxy");
            entryPoint.commence(request, response,
                    new PartnerAuthenticationException("Client certificate assertion not accepted"));
            return;
        }

        Optional<ClientCertificateDetails> certificate = xfcc != null
                ? XfccParser.parse(xfcc).stream().findFirst()
                : fromContainer(request);
        if (certificate.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        Optional<PartnerIdentity> identity = identityResolver.resolve(certificate.get());
        if (identity.isEmpty()) {
            log.warn("No partner registered for certificate subject='{}' uris={} hash={}",
                    certificate.get().subjectDn(), certificate.get().uriSans(), certificate.get().hash());
            metrics.recordAuthenticationFailed(PrincipalType.PARTNER.name(), "unknown-certificate");
            entryPoint.commence(request, response,
                    new PartnerAuthenticationException("Client certificate is not recognized"));
            return;
        }

        authenticate(identity.get(), certificate.get());
        chain.doFilter(request, response);
    }

    /**
     * Note that a partner with no grant is still authenticated, just with no authorities. The request
     * then fails the endpoint's authorization rule and gets a 403, which is the honest answer: the
     * certificate is genuine, the access is not granted. Returning a 401 here would tell the partner to
     * go and re-authenticate, which would not help them and would hide the real cause from support.
     */
    private void authenticate(PartnerIdentity identity, ClientCertificateDetails certificate) {
        PrincipalRef ref = new PrincipalRef(identity.type(), identity.partnerId());
        Authorities authorities = authorityLookup.lookup(ref);

        LudwigPrincipal.LudwigPrincipalBuilder principal = LudwigPrincipal.builder()
                .subject(identity.partnerId())
                .type(identity.type())
                .displayName(identity.displayName())
                .tenantId(authorities.attributes().get(Authorities.TENANT_ATTRIBUTE))
                .roles(authorities.roles())
                .permissions(authorities.permissions())
                .attributes(authorities.attributes());
        if (certificate.hash() != null) {
            // Kept for the audit trail: which certificate was used, without re-deriving identity from it.
            principal.attribute("certificate.hash", certificate.hash());
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new LudwigAuthentication(principal.build()));
        SecurityContextHolder.setContext(context);
        metrics.recordAuthenticated(identity.type().name());
    }

    /** Direct-termination fallback: build the same view from the container's verified chain. */
    private Optional<ClientCertificateDetails> fromContainer(HttpServletRequest request) {
        if (!(request.getAttribute(X509_ATTRIBUTE) instanceof X509Certificate[] chain) || chain.length == 0) {
            return Optional.empty();
        }
        X509Certificate certificate = chain[0];
        ClientCertificateDetails.ClientCertificateDetailsBuilder builder = ClientCertificateDetails.builder()
                .subjectDn(certificate.getSubjectX500Principal().getName())
                .hash(fingerprint(certificate));
        subjectAlternativeNames(certificate).forEach(entry -> {
            Integer type = (Integer) entry.get(0);
            String value = String.valueOf(entry.get(1));
            if (type == SAN_TYPE_URI) {
                builder.uriSan(value);
            } else if (type == SAN_TYPE_DNS) {
                builder.dnsSan(value);
            }
        });
        return Optional.of(builder.build());
    }

    private Collection<List<?>> subjectAlternativeNames(X509Certificate certificate) {
        try {
            Collection<List<?>> names = certificate.getSubjectAlternativeNames();
            return names == null ? List.of() : names;
        } catch (java.security.cert.CertificateParsingException e) {
            log.warn("Could not read subject alternative names from client certificate", e);
            return List.of();
        }
    }

    private String fingerprint(X509Certificate certificate) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(certificate.getEncoded()));
        } catch (CertificateEncodingException | java.security.NoSuchAlgorithmException e) {
            log.warn("Could not compute client certificate fingerprint", e);
            return null;
        }
    }
}
