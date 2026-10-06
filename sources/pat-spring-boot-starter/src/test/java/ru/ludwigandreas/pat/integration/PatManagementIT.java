package ru.ludwigandreas.pat.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.pat.web.PatAuthorities;
import ru.ludwigandreas.security.principal.Credential;
import ru.ludwigandreas.security.principal.LudwigAuthentication;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;
import ru.ludwigandreas.testsupport.image.LudwigTestImages;

/**
 * The management API end to end, against a real Postgres and the real filter chain.
 *
 * <p>Four claims, and the fourth is the one that would be easiest to get wrong and hardest to notice:
 *
 * <ol>
 *   <li>an unauthenticated request is refused;</li>
 *   <li>a caller can issue a token for themselves, and the secret comes back exactly once;</li>
 *   <li>a caller without {@code pat:manage:any} cannot issue for somebody else;</li>
 *   <li><b>a token-backed caller is refused on every endpoint</b>, whatever their scopes say.</li>
 * </ol>
 *
 * <p>The fourth is what stops a leaked read-only token being a persistence mechanism - exchange it, mint a
 * wider one, and revoking the original accomplishes nothing. It is asserted against <em>every</em> endpoint
 * rather than against issuance alone, because a guard registered for one path pattern and not another is
 * exactly the shape of mistake that passes a single-endpoint test.
 *
 * <p>Named {@code ...IT}: this starts a container, so it runs at {@code verify} under failsafe and not on
 * every {@code mvn test} of every module that depends on this one. Per the repository's surefire/failsafe
 * split, a test named otherwise would simply never run.
 *
 * <p>The image comes from {@link LudwigTestImages}, which is the single place in this repository a digest
 * appears. Writing the pin out here would have been the twenty-second copy of the same literal, and
 * {@code LudwigTestImages} refuses to initialise if a pin lacks an {@code @sha256:} segment - so using it
 * is also how this test inherits the container-pinning policy rather than restating it.
 */
@SpringBootTest(classes = ExchangeTestSupport.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class PatManagementIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(LudwigTestImages.POSTGRES);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ExchangeTestSupport.RecordingSink auditSink;

    private static final String BASE = "/api/v1/personal-access-tokens";

    private static String issueBody(String owner) throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of(
                "owner", owner == null ? "" : owner,
                "name", "ci-deploy",
                "scopes", List.of("orders:read"),
                "audiences", List.of("deploy-service"),
                "lifetime", "P30D"));
    }

    /** Authenticates the ambient context as a session-derived caller. */
    private static void asUser(String subject, String... permissions) {
        LudwigPrincipal principal = LudwigPrincipal.builder()
                .subject(subject)
                .type(PrincipalType.USER)
                .permissions(java.util.Set.of(permissions))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new LudwigAuthentication(principal));
    }

    /** Authenticates the ambient context as a caller whose credential is a personal access token. */
    private static void asTokenBackedUser(String subject, String... permissions) {
        LudwigPrincipal principal = LudwigPrincipal.builder()
                .subject(subject)
                .type(PrincipalType.USER)
                .permissions(java.util.Set.of(permissions))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new LudwigAuthentication(principal, Credential.personalAccessToken("pat-leaked")));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("an unauthenticated request is refused, by the security starter rather than by this module")
    void anonymousIsRefused() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(issueBody(null)))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("an unauthenticated caller must not reach the controller")
                        .isIn(401, 403));
    }

    @Test
    @DisplayName("a caller issues a token for themselves and the secret is returned exactly once")
    void selfIssuanceReturnsTheSecretOnce() throws Exception {
        asUser("alice", PatAuthorities.MANAGE_OWN, "orders:read");

        String body = mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueBody(null)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(201))
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> response = json.readValue(body, Map.class);
        String secret = (String) response.get("secret");
        assertThat(secret).startsWith("lpat_");

        @SuppressWarnings("unchecked")
        Map<String, Object> token = (Map<String, Object>) response.get("token");
        String id = (String) token.get("id");

        // The read endpoints return a type with no secret field at all, so this is structural rather than
        // a code path being right. Asserted anyway, because the claim is worth a test even when the type
        // system is making it.
        String reread = mockMvc.perform(get(BASE + "/" + id))
                .andReturn().getResponse().getContentAsString();
        assertThat(reread).doesNotContain(secret).doesNotContain("lpat_");

        String listed = mockMvc.perform(get(BASE)).andReturn().getResponse().getContentAsString();
        assertThat(listed).doesNotContain(secret).doesNotContain("lpat_");

        assertThat(auditSink.actions()).contains("pat.issued");
    }

    @Test
    @DisplayName("issuing for another subject without pat:manage:any is refused")
    void issuingForAnotherSubjectIsRefused() throws Exception {
        asUser("alice", PatAuthorities.MANAGE_OWN);

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(issueBody("bob")))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(403));
    }

    @Test
    @DisplayName("issuing for another subject with pat:manage:any succeeds")
    void issuingForAnotherSubjectIsAllowedWithTheAuthority() throws Exception {
        asUser("admin", PatAuthorities.MANAGE_OWN, PatAuthorities.MANAGE_ANY);

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(issueBody("bob")))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(201));

        // The issuer is recorded even though it is not the owner, which is what makes "tokens minted on
        // behalf of somebody else" a filter rather than a join against a separate source.
        AuditEvent issued = auditSink.lastOf("pat.issued");
        assertThat(issued.actor().subject()).isEqualTo("bob");
        assertThat(issued.attributes()).containsEntry("issuedBy", "admin");
    }

    /**
     * The rule that a token may not operate on tokens, asserted against the whole surface.
     *
     * <p>Nested so each endpoint is its own test with its own name: a single test looping over five paths
     * reports one failure for whichever path it reached first, which is the least useful thing it could say
     * when the guard's path pattern is the likely cause.
     */
    @Nested
    @DisplayName("a token-backed caller is refused on every endpoint")
    class TokenBackedCallerIsRefused {

        /** Generous permissions on purpose: the refusal must not depend on the caller lacking authority. */
        private org.springframework.test.web.servlet.request.RequestPostProcessor leakedToken() {
            LudwigPrincipal principal = LudwigPrincipal.builder()
                    .subject("alice")
                    .type(PrincipalType.USER)
                    .permissions(java.util.Set.of(
                            PatAuthorities.MANAGE_OWN, PatAuthorities.MANAGE_ANY, "orders:read"))
                    .build();
            return org.springframework.security.test.web.servlet.request
                    .SecurityMockMvcRequestPostProcessors.authentication(
                            new LudwigAuthentication(principal,
                                    Credential.personalAccessToken("pat-leaked")));
        }

        /**
         * Asserts the refusal came from the <b>credential guard</b>, not merely that it was a 403.
         *
         * <p>The body check is the load-bearing half and was missing at first. {@code @PreAuthorize} also
         * produces a 403, so a status-only assertion passes whether the guard fired or the caller simply
         * lacked authority - and it would keep passing if the guard were never registered at all. The
         * caller here is given both management permissions precisely so that authorization cannot be the
         * reason, and the problem code is what proves which check refused it.
         */
        private void assertRefusedByTheGuard(ResultActions actions) throws Exception {
            actions.andExpect(result -> {
                assertThat(result.getResponse().getStatus())
                        .as("a token-backed caller must be refused with 403, not 401 - their identity is"
                                + " established and it is the credential that is refused, so telling them"
                                + " to re-authenticate would send them round a loop they can complete")
                        .isEqualTo(403);
                assertThat(result.getResponse().getContentAsString())
                        .as("the refusal must come from the credential guard, not from @PreAuthorize")
                        .contains("ludwig.pat.credential-not-permitted");
            });
        }

        @Test
        @DisplayName("issuance")
        void issuance() throws Exception {
            assertRefusedByTheGuard(mockMvc.perform(post(BASE)
                    .with(leakedToken())
                    .contentType(MediaType.APPLICATION_JSON).content(issueBody(null))));
        }

        @Test
        @DisplayName("listing")
        void listing() throws Exception {
            assertRefusedByTheGuard(mockMvc.perform(get(BASE).with(leakedToken())));
        }

        @Test
        @DisplayName("reading one token")
        void reading() throws Exception {
            assertRefusedByTheGuard(mockMvc.perform(
                    get(BASE + "/11111111-2222-3333-4444-555555555555").with(leakedToken())));
        }

        @Test
        @DisplayName("rotation - the one that would turn a leak into a wider leak")
        void rotation() throws Exception {
            assertRefusedByTheGuard(mockMvc.perform(
                    post(BASE + "/11111111-2222-3333-4444-555555555555/rotations")
                            .with(leakedToken())));
        }

        @Test
        @DisplayName("revocation - the one that would let a leaked token hide its own tracks")
        void revocation() throws Exception {
            assertRefusedByTheGuard(mockMvc.perform(
                    delete(BASE + "/11111111-2222-3333-4444-555555555555").with(leakedToken())));
        }
    }

}
