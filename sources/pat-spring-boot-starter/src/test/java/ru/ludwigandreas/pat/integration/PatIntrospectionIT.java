package ru.ludwigandreas.pat.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import ru.ludwigandreas.pat.service.IssueTokenCommand;
import ru.ludwigandreas.pat.service.PatService;
import ru.ludwigandreas.security.principal.Credential;
import ru.ludwigandreas.security.principal.LudwigAuthentication;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;
import ru.ludwigandreas.testsupport.image.LudwigTestImages;

/**
 * The introspection endpoint: what it answers for a live token, and that a token cannot use it.
 *
 * <p>Note what the caller must be: introspection requires an <b>authenticated</b> caller, unlike the
 * exchange. The exchange consumes a token as its subject and so has to be reachable unauthenticated;
 * introspection is one service asking another about a credential, so the asker authenticates with its own
 * workload identity.
 *
 * <p>The second half is the one that matters. A leaked token able to introspect could enumerate which
 * tokens are live - reconnaissance the uniform failure everywhere else is specifically designed to deny.
 * So the endpoint is guarded exactly as the management surface is, and this suite asserts the problem
 * <em>code</em> rather than merely a 403, because `@PreAuthorize` produces one of those too.
 */
@SpringBootTest(classes = ExchangeTestSupport.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class PatIntrospectionIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(LudwigTestImages.POSTGRES);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PatService service;

    @Autowired
    private ObjectMapper json;

    private String issue() {
        return service.issue(
                new IssueTokenCommand("alice", "ci", Set.of("orders:read", "ROLE_READER"),
                        Set.of("deploy-service"), Duration.ofDays(30), List.of()),
                "alice", Set.of()).rendered();
    }

    /**
     * The calling service's own identity.
     *
     * <p>Introspection requires an authenticated caller, which is the opposite of the exchange and is the
     * right way round. The exchange <em>consumes</em> a token as its subject, so it must be reachable
     * unauthenticated; introspection is one service asking another a question, so the asker is a
     * {@code SERVICE} principal authenticated by its workload identity.
     *
     * <p>This suite returned 401 everywhere before this existed, which is the endpoint's access control
     * working rather than a defect - worth recording, because a test that had been "fixed" by making the
     * path public would have quietly removed the control.
     */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor asCallingService() {
        LudwigPrincipal principal = LudwigPrincipal.builder()
                .subject("spiffe://cluster/ns/deploy/sa/deploy-service")
                .type(PrincipalType.SERVICE)
                .build();
        return org.springframework.security.test.web.servlet.request
                .SecurityMockMvcRequestPostProcessors.authentication(
                        new LudwigAuthentication(principal));
    }

    private Map<?, ?> introspect(String token, String resource) throws Exception {
        var request = post("/introspect")
                .with(asCallingService())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token", token);
        if (resource != null) {
            request = request.param("resource", resource);
        }
        String body = mockMvc.perform(request)
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200))
                .andReturn().getResponse().getContentAsString();
        return json.readValue(body, Map.class);
    }

    @Test
    @DisplayName("a live token reports active with its owner, scopes, audiences and expiry")
    void liveTokenReportsActive() throws Exception {
        Map<?, ?> response = introspect(issue(), "deploy-service");

        assertThat(response.get("active")).isEqualTo(true);
        assertThat(response.get("sub")).isEqualTo("alice");
        assertThat((String) response.get("scope"))
                .contains("orders:read")
                .contains("ROLE_READER");
        assertThat(((List<?>) response.get("aud")).stream().map(String::valueOf).toList())
                .containsExactly("deploy-service");
        assertThat(response.get("patId")).isNotNull();
        assertThat(response.get("exp")).isNotNull();
    }

    @Test
    @DisplayName("the response carries no secret, digest or key id")
    void responseCarriesNoCredentialMaterial() throws Exception {
        String token = issue();
        Map<?, ?> response = introspect(token, "deploy-service");

        // Asserted on the wire, not only on the type. The type-level assertion is in pat-core; this is
        // the one that would catch a serializer configured to include something the record does not
        // declare, or a controller adding a field on the way out.
        assertThat(response.keySet().stream().map(String::valueOf).toList())
                .containsExactlyInAnyOrder("active", "sub", "scope", "aud", "patId", "exp");
        assertThat(json.writeValueAsString(response))
                .doesNotContain(token)
                .doesNotContain("lpat_")
                .doesNotContain("digest")
                .doesNotContain("keyId");
    }

    @Test
    @DisplayName("an audience the token does not permit reports inactive, not an error")
    void unpermittedAudienceReportsInactive() throws Exception {
        assertThat(introspect(issue(), "billing-service").get("active")).isEqualTo(false);
    }

    @Test
    @DisplayName("an omitted resource reports inactive - there is deliberately no default")
    void omittedResourceReportsInactive() throws Exception {
        assertThat(introspect(issue(), null).get("active")).isEqualTo(false);
    }

    @Test
    @DisplayName("an unusable token is a 200 with active=false, not a 401")
    void unusableTokenIsASuccessfulAnswer() throws Exception {
        // RFC 7662's model, and the distinction matters to the caller: a 401 would read as "your
        // introspection call was unauthorized" rather than "the token you asked about is not usable",
        // which sends an operator to the wrong place.
        assertThat(introspect("not-a-token", "deploy-service").get("active")).isEqualTo(false);
    }

    @Test
    @DisplayName("a token-backed caller cannot introspect, and is refused by the credential guard")
    void tokenBackedCallerCannotIntrospect() throws Exception {
        LudwigPrincipal principal = LudwigPrincipal.builder()
                .subject("alice").type(PrincipalType.USER).build();

        mockMvc.perform(post("/introspect")
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.authentication(
                                        new LudwigAuthentication(principal,
                                                Credential.personalAccessToken("pat-leaked"))))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", issue())
                        .param("resource", "deploy-service"))
                .andExpect(result -> {
                    assertThat(result.getResponse().getStatus()).isEqualTo(403);
                    // The problem code, not merely the status: @PreAuthorize also produces a 403, so a
                    // status-only assertion would pass whether the guard fired or not.
                    assertThat(result.getResponse().getContentAsString())
                            .as("the refusal must come from the credential guard")
                            .contains("ludwig.pat.credential-not-permitted");
                });
    }
}
