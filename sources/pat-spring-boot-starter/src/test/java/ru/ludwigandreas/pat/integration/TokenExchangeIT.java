package ru.ludwigandreas.pat.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.ludwigandreas.pat.claim.PatClaims;
import ru.ludwigandreas.pat.service.IssueTokenCommand;
import ru.ludwigandreas.pat.service.PatService;
import ru.ludwigandreas.pat.service.RevocationReasons;
import ru.ludwigandreas.testsupport.image.LudwigTestImages;

/**
 * The exchange end to end: what the assertion carries, and - more importantly - what it does not.
 *
 * <p>The claim assertions here are the ones worth having. A test that only checked the assertion verifies
 * would pass for an assertion carrying the owner's entire authority, which is the single most consequential
 * mistake available in this module and the one a well-meaning optimisation would introduce.
 */
@SpringBootTest(classes = ExchangeTestSupport.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class TokenExchangeIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(LudwigTestImages.POSTGRES);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final String EXCHANGE = "/oauth2/token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PatService service;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ExchangeTestSupport.RecordingMetrics metrics;

    @BeforeEach
    void reset() {
        metrics.clear();
    }

    private String issueToken(Set<String> audiences) {
        return service.issue(
                new IssueTokenCommand("alice", "ci", Set.of("orders:read", "ROLE_READER"),
                        audiences, Duration.ofDays(30), List.of()),
                "alice", Set.of()).rendered();
    }

    private org.springframework.test.web.servlet.ResultActions exchange(String token, String audience)
            throws Exception {
        var request = post(EXCHANGE)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
                .param("subject_token", token)
                .param("subject_token_type", "urn:ludwig:params:oauth:token-type:pat");
        if (audience != null) {
            request = request.param("resource", audience);
        }
        return mockMvc.perform(request);
    }

    private SignedJWT assertionFrom(String body) throws Exception {
        Map<?, ?> response = json.readValue(body, Map.class);
        return SignedJWT.parse((String) response.get("access_token"));
    }

    @Test
    @DisplayName("a valid exchange returns an assertion for the one requested audience")
    void validExchangeMintsForOneAudience() throws Exception {
        String token = issueToken(Set.of("deploy-service", "billing-service"));

        String body = exchange(token, "deploy-service")
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200))
                .andReturn().getResponse().getContentAsString();

        SignedJWT assertion = assertionFrom(body);
        assertThat(assertion.getJWTClaimsSet().getSubject()).isEqualTo("alice");
        // Exactly one, and not the token's whole set. An assertion naming both would be valid at both,
        // which reintroduces in miniature the replay AudienceValidator exists to prevent.
        assertThat(assertion.getJWTClaimsSet().getAudience()).containsExactly("deploy-service");
        assertThat(metrics.exchanges()).containsExactly("deploy-service");
    }

    @Test
    @DisplayName("the assertion carries the ludwig_pat claim with the attenuation")
    void assertionCarriesTheAttenuation() throws Exception {
        String token = issueToken(Set.of("deploy-service"));

        SignedJWT assertion = assertionFrom(
                exchange(token, "deploy-service").andReturn().getResponse().getContentAsString());

        @SuppressWarnings("unchecked")
        Map<String, Object> claim =
                (Map<String, Object>) assertion.getJWTClaimsSet().getClaim(PatClaims.CLAIM);
        assertThat(claim).containsKey(PatClaims.ID);
        List<String> scopes = ((List<?>) claim.get(PatClaims.SCOPES)).stream()
                .map(String::valueOf)
                .toList();
        assertThat(scopes).containsExactlyInAnyOrder("orders:read", "ROLE_READER");
    }

    @Test
    @DisplayName("the assertion carries NO role or permission claim, which is the whole design")
    void assertionCarriesNoEntitlement() throws Exception {
        String token = issueToken(Set.of("deploy-service"));

        SignedJWT assertion = assertionFrom(
                exchange(token, "deploy-service").andReturn().getResponse().getContentAsString());

        // The assertion this class exists for. Baking effective authority in here would save every service
        // a lookup and would make this five-minute assertion five minutes of frozen privilege, resolved
        // from the issuer's view of the owner rather than each service's - two sources of truth for
        // entitlement, which is exactly what local role resolution exists to prevent.
        assertThat(assertion.getJWTClaimsSet().getClaims())
                .doesNotContainKeys("roles", "authorities", "permissions", "scope", "realm_access",
                        "resource_access", "groups");
    }

    @Test
    @DisplayName("the response declares its own cacheable lifetime, which the edge caches by")
    void responseDeclaresItsLifetime() throws Exception {
        String token = issueToken(Set.of("deploy-service"));

        Map<?, ?> response = json.readValue(
                exchange(token, "deploy-service").andReturn().getResponse().getContentAsString(),
                Map.class);

        // Without this the edge has to invent a number, and the safe number to invent is zero - at which
        // point every service request becomes an issuer request by proxy.
        assertThat(((Number) response.get("expires_in")).longValue()).isEqualTo(300L);
        assertThat(response.get("token_type")).isEqualTo("Bearer");
        assertThat(response.get("issued_token_type")).isEqualTo("urn:ietf:params:oauth:token-type:jwt");
    }

    @Test
    @DisplayName("an audience the token does not permit is refused")
    void unpermittedAudienceIsRefused() throws Exception {
        String token = issueToken(Set.of("deploy-service"));

        exchange(token, "billing-service")
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
        assertThat(metrics.failures()).containsExactly("audience-not-permitted");
    }

    @Test
    @DisplayName("an omitted audience is refused - there is deliberately no default")
    void omittedAudienceIsRefused() throws Exception {
        String token = issueToken(Set.of("deploy-service"));

        exchange(token, null)
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
        assertThat(metrics.failures()).containsExactly("audience-missing");
    }

    @Test
    @DisplayName("an audience this issuer does not mint for is refused before the token is read")
    void unissuableAudienceIsRefused() throws Exception {
        String token = issueToken(Set.of("deploy-service"));

        exchange(token, "some-other-service")
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
        assertThat(metrics.failures()).containsExactly("audience-not-issuable");
    }

    @Test
    @DisplayName("a revoked token is refused and the attempt is audited as a security signal")
    void revokedTokenIsRefusedAndAudited() throws Exception {
        var issued = service.issue(
                new IssueTokenCommand("alice", "ci", Set.of("orders:read"),
                        Set.of("deploy-service"), Duration.ofDays(30), List.of()),
                "alice", Set.of());
        service.revoke(issued.token().id(), "operator", RevocationReasons.COMPROMISED);

        exchange(issued.rendered(), "deploy-service")
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
        assertThat(metrics.failures()).containsExactly("revoked");
    }

    @Test
    @DisplayName("a rotated token's previous secret still works during the overlap")
    void previousSecretWorksDuringOverlap() throws Exception {
        var issued = service.issue(
                new IssueTokenCommand("alice", "ci", Set.of("orders:read"),
                        Set.of("deploy-service"), Duration.ofDays(30), List.of()),
                "alice", Set.of());
        String oldSecret = issued.rendered();
        String newSecret = service.rotate(issued.token().id(), "alice").rendered();

        // Both, because a rotation that invalidated the old secret immediately would require every
        // consumer to be updated atomically - which nothing real can do, so nobody would rotate.
        exchange(newSecret, "deploy-service")
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
        exchange(oldSecret, "deploy-service")
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
    }

    @Test
    @DisplayName("the wrong grant type is the uniform refusal, not a distinct error")
    void wrongGrantTypeIsTheUniformRefusal() throws Exception {
        mockMvc.perform(post(EXCHANGE)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("subject_token", issueToken(Set.of("deploy-service")))
                        .param("resource", "deploy-service"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
    }
}
