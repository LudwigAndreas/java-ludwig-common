package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import ru.ludwigandreas.security.authn.mtls.MutualTlsAuthenticationFilter;
import ru.ludwigandreas.security.authn.mtls.PartnerIdentity;
import ru.ludwigandreas.security.authn.mtls.PartnerIdentityResolver;
import ru.ludwigandreas.security.authn.mtls.TrustedProxies;
import ru.ludwigandreas.security.authz.Authorities;
import ru.ludwigandreas.security.authz.AuthorityLookup;
import ru.ludwigandreas.security.metrics.SecurityMetrics;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;
import ru.ludwigandreas.security.principal.SecurityPrincipals;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MutualTlsAuthenticationFilterTest {

    private static final String XFCC =
            "By=spiffe://mesh/ns/edge/sa/gateway;Hash=abc;URI=spiffe://partners/acme";

    @Mock
    private PartnerIdentityResolver identityResolver;

    @Mock
    private AuthorityLookup authorityLookup;

    @Mock
    private AuthenticationEntryPoint entryPoint;

    @Mock
    private SecurityMetrics metrics;

    private MutualTlsAuthenticationFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new MutualTlsAuthenticationFilter(identityResolver, authorityLookup,
                new TrustedProxies(List.of("10.4.0.0/16")), entryPoint, metrics);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * The whole mTLS path rests on this: the header is only a verified fact because only the proxy can
     * reach the port. From anywhere else it is a forgery attempt, and it is rejected loudly rather than
     * quietly ignored - the metric is what tells an operator someone is reaching the service directly.
     */
    @Test
    @DisplayName("a client-certificate header from an untrusted peer is rejected, not ignored")
    void rejectsForgedHeaderFromUntrustedPeer() throws Exception {
        request.setRemoteAddr("203.0.113.9");
        request.addHeader(MutualTlsAuthenticationFilter.XFCC_HEADER, XFCC);

        filter.doFilter(request, response, chain);

        verify(entryPoint).commence(any(), any(), any());
        verify(metrics).recordAuthenticationFailed(PrincipalType.PARTNER.name(), "untrusted-proxy");
        verify(identityResolver, never()).resolve(any());
        assertThat(chain.getRequest()).as("the request must not continue down the chain").isNull();
        assertThat(SecurityPrincipals.current()).isEmpty();
    }

    @Test
    void authenticatesAKnownPartnerFromTheProxy() throws Exception {
        request.setRemoteAddr("10.4.0.1");
        request.addHeader(MutualTlsAuthenticationFilter.XFCC_HEADER, XFCC);
        when(identityResolver.resolve(any()))
                .thenReturn(Optional.of(PartnerIdentity.partner("acme", "ACME GmbH")));
        when(authorityLookup.lookup(any()))
                .thenReturn(Authorities.builder().roles(Set.of("CATALOG_PARTNER")).build());

        filter.doFilter(request, response, chain);

        LudwigPrincipal principal = SecurityPrincipals.require();
        assertThat(principal.subject()).isEqualTo("acme");
        assertThat(principal.type()).isEqualTo(PrincipalType.PARTNER);
        assertThat(principal.roles()).containsExactly("ROLE_CATALOG_PARTNER");
        assertThat(principal.attribute("certificate.hash")).contains("abc");
        assertThat(chain.getRequest()).isNotNull();
        verify(metrics).recordAuthenticated(PrincipalType.PARTNER.name());
    }

    @Test
    @DisplayName("a certificate from a trusted CA that is not a registered partner is not a caller")
    void rejectsUnregisteredCertificate() throws Exception {
        request.setRemoteAddr("10.4.0.1");
        request.addHeader(MutualTlsAuthenticationFilter.XFCC_HEADER, XFCC);
        when(identityResolver.resolve(any())).thenReturn(Optional.empty());

        filter.doFilter(request, response, chain);

        verify(entryPoint).commence(any(), any(), any());
        verify(metrics).recordAuthenticationFailed(PrincipalType.PARTNER.name(), "unknown-certificate");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("no certificate is not a failure - the JWT filter downstream handles browser users")
    void passesThroughWhenNoCertificateIsPresented() throws Exception {
        request.setRemoteAddr("10.4.0.1");

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityPrincipals.current()).isEmpty();
        verify(entryPoint, never()).commence(any(), any(), any());
    }

    @Test
    void leavesAnAlreadyAuthenticatedRequestAlone() throws Exception {
        request.setRemoteAddr("10.4.0.1");
        request.addHeader(MutualTlsAuthenticationFilter.XFCC_HEADER, XFCC);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new ru.ludwigandreas.security.principal.LudwigAuthentication(
                LudwigPrincipal.builder().subject("already").type(PrincipalType.USER).build()));
        SecurityContextHolder.setContext(context);

        filter.doFilter(request, response, chain);

        assertThat(SecurityPrincipals.require().subject()).isEqualTo("already");
        verify(identityResolver, never()).resolve(any());
        assertThat(chain.getRequest()).isNotNull();
    }
}
