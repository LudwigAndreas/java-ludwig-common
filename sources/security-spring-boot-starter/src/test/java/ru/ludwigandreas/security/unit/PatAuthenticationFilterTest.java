package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
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
import ru.ludwigandreas.cache.api.LudwigCache;
import ru.ludwigandreas.pat.introspection.PatIntrospectionResponse;
import ru.ludwigandreas.pat.token.PatTokens;
import ru.ludwigandreas.security.authn.pat.PatAuthenticationFilter;
import ru.ludwigandreas.security.authn.pat.PatIntrospectionClient;
import ru.ludwigandreas.security.authz.Authorities;
import ru.ludwigandreas.security.authz.AuthorityLookup;
import ru.ludwigandreas.security.metrics.SecurityMetrics;
import ru.ludwigandreas.security.principal.LudwigAuthentication;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;

/**
 * The direct PAT authentication path, and the four behaviours that make it safe to enable.
 *
 * <p>Modelled on {@code MutualTlsAuthenticationFilterTest} deliberately: the two filters sit next to each
 * other in the chain, make the same argument for running before the bearer filter, and have the same
 * obligation to leave a request they do not recognise exactly as they found it. Testing them the same way
 * makes a divergence between them visible.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PatAuthenticationFilterTest {

    @Mock
    private PatIntrospectionClient client;

    @Mock
    private LudwigCache<String, PatIntrospectionResponse> cache;

    @Mock
    private AuthorityLookup authorityLookup;

    @Mock
    private SecurityMetrics metrics;

    private PatAuthenticationFilter filter;

    private PatTokens.MintedToken minted;

    @BeforeEach
    void setUp() {
        filter = new PatAuthenticationFilter(client, cache, authorityLookup, metrics);
        // A real minted token, so the filter's own parse and checksum run rather than being bypassed by a
        // hand-written string that happens to start with the prefix.
        minted = PatTokens.mint();
        when(cache.get(anyString())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a live token authenticates with the intersection, not the owner's full authority")
    void authenticatesWithTheIntersection() throws Exception {
        // The owner holds three roles. The token is scoped to one of them plus one the owner does not
        // hold - which is the case that distinguishes an intersection from a union, and the case a
        // compromised issuer would exploit if the scope set were trusted on its own.
        when(authorityLookup.lookup(any())).thenReturn(Authorities.builder()
                .roles(Set.of("DEPLOYER", "ADMIN", "AUDITOR"))
                .permissions(Set.of("deploy:write", "users:write"))
                .build());
        when(client.introspect(anyString())).thenReturn(Optional.of(PatIntrospectionResponse.active(
                "alice", Set.of("DEPLOYER", "ROOT", "deploy:write"), Set.of("deploy-service"),
                "pat-7", Instant.now().plusSeconds(3600))));

        invoke(minted.rendered());

        LudwigAuthentication authentication =
                (LudwigAuthentication) SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        LudwigPrincipal principal = authentication.getPrincipal();
        assertThat(principal.subject()).isEqualTo("alice");
        assertThat(principal.roles())
                .as("ROLE_ROOT was in the token's scopes and not in the owner's authority, so it must not"
                        + " survive the intersection")
                .containsExactly("ROLE_DEPLOYER");
        assertThat(principal.permissions()).containsExactly("deploy:write");
        assertThat(authentication.credential().credentialId()).contains("pat-7");
        verify(metrics).recordAuthenticated(PrincipalType.USER.name());
    }

    @Test
    @DisplayName("an inactive token does not authenticate, and does not reject the request here either")
    void inactiveTokenDoesNotAuthenticate() throws Exception {
        when(client.introspect(anyString())).thenReturn(Optional.empty());

        MockFilterChain chain = invoke(minted.rendered());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        // The chain still ran. The refusal is the entry point's single localized 401, not a second one
        // emitted from inside this filter - see the filter's own javadoc for why that matters.
        assertThat(chain.getRequest()).isNotNull();
        verify(metrics).recordAuthenticationFailed(PrincipalType.USER.name(), "pat-inactive");
        verify(metrics, never()).recordAuthenticated(anyString());
    }

    @Test
    @DisplayName("an ordinary bearer JWT passes through, so enabling this path cannot change JWT handling")
    void ordinaryJwtPassesThrough() throws Exception {
        invoke("eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.signature");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(client, never()).introspect(anyString());
        verify(authorityLookup, never()).lookup(any());
    }

    @Test
    @DisplayName("an already-authenticated request is untouched, so chain order is not a security decision")
    void alreadyAuthenticatedRequestIsUntouched() throws Exception {
        LudwigAuthentication existing = new LudwigAuthentication(LudwigPrincipal.builder()
                .subject("service-a")
                .type(PrincipalType.SERVICE)
                .displayName("service-a")
                .roles(Set.of("INTERNAL"))
                .build());
        SecurityContextHolder.getContext().setAuthentication(existing);

        invoke(minted.rendered());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(existing);
        verify(client, never()).introspect(anyString());
    }

    @Test
    @DisplayName("a malformed lpat_ credential costs no network call at all")
    void malformedCredentialIsNotIntrospected() throws Exception {
        // Prefixed, so it reaches the verify step, but with a wrong part count. The parse rejects it from
        // CPU, which is the ordering the javadoc states: the one endpoint an attacker can aim at should
        // answer garbage without doing I/O.
        invoke("lpat_notatoken");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(client, never()).introspect(anyString());
    }

    private MockFilterChain invoke(String credential) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/things");
        request.addHeader("Authorization", "Bearer " + credential);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }
}
