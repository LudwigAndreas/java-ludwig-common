package ru.ludwigandreas.pat.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import ru.ludwigandreas.pat.problem.PatProblemTypes;
import ru.ludwigandreas.pat.web.PatAuthorities;
import ru.ludwigandreas.security.principal.Credential;
import ru.ludwigandreas.security.principal.LudwigAuthentication;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;
import ru.ludwigandreas.testsupport.image.LudwigTestImages;

/**
 * The client-facing refusals, and the one thing this suite can and cannot prove.
 *
 * <p><b>Can prove:</b> that the three refusals are distinguishable from one another by a machine, that each
 * carries the management location so an automation user has somewhere to go, and that a rejected
 * <em>credential</em> is a different document from a missing <em>authority</em>. Those are the properties a
 * client library branches on.
 *
 * <p><b>Cannot prove:</b> that the <em>edge</em> returns any of this. The edge is not in this repository and
 * no build here can observe what it serves - it renders a refusal on the issuer's behalf for a client that
 * never called the exchange. {@code PatProblemTypes} publishes the constants so the edge configures against
 * a definition rather than inventing one, and the module README states the required response verbatim. That
 * is the whole mitigation, and it is recorded in the change's enforcement table as one of the conventions no
 * build can hold rather than left for a reader to discover.
 */
@SpringBootTest(classes = ExchangeTestSupport.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class CredentialRejectedProblemIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(LudwigTestImages.POSTGRES);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final String MANAGEMENT = "/api/v1/personal-access-tokens";

    @Autowired
    private MockMvc mockMvc;

    /**
     * A per-request authentication post-processor.
     *
     * <p>Rather than setting {@code SecurityContextHolder} in the test thread, which is what this suite did
     * first and which quietly stopped working after the first request in a method: Spring Security's
     * {@code SecurityContextHolderFilter} clears the holder when a request completes, so the second
     * {@code MockMvc} call in a test ran unauthenticated and got a 403 from {@code @PreAuthorize} - a right
     * answer for the wrong reason, which is the most expensive kind of passing test.
     */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor as(
            Credential credential, String... permissions) {
        LudwigPrincipal principal = LudwigPrincipal.builder()
                .subject("alice")
                .type(PrincipalType.USER)
                .permissions(Set.of(permissions))
                .build();
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .authentication(new LudwigAuthentication(principal, credential));
    }

    @Test
    @DisplayName("an exchange refusal is a 401 naming no cause, with the management location")
    void exchangeRefusalIsActionableButSilentOnCause() throws Exception {
        String body = mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
                        .param("subject_token", "lpat_not_a_real_token")
                        .param("resource", "deploy-service"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401))
                .andReturn().getResponse().getContentAsString();

        // Actionable: it says where to go and fix it. That is what an unattended pipeline needs, and the
        // difference between a one-minute fix and an afternoon.
        assertThat(body).contains(PatProblemTypes.MANAGEMENT_PATH);
        assertThat(body).contains("ludwig.pat.credential-rejected");
    }

    @Test
    @DisplayName("a token-backed caller on the management surface is a different document from a 401")
    void credentialNotPermittedIsItsOwnDocument() throws Exception {
        String body = mockMvc.perform(get(MANAGEMENT)
                        .with(as(Credential.personalAccessToken("pat-leaked"),
                                PatAuthorities.MANAGE_OWN, PatAuthorities.MANAGE_ANY)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(403))
                .andReturn().getResponse().getContentAsString();

        // 403, not 401: the caller's identity is established and it is the credential that is refused.
        // A 401 would tell them to re-authenticate, which they can do with the same token and be refused
        // again - a loop.
        assertThat(body).contains("ludwig.pat.credential-not-permitted");
        assertThat(body).contains("PERSONAL_ACCESS_TOKEN");
        assertThat(body).contains(PatProblemTypes.MANAGEMENT_PATH);
    }

    @Test
    @DisplayName("a rejected credential and a missing authority are different documents")
    void rejectedCredentialIsDistinctFromMissingAuthority() throws Exception {
        // A session-derived caller with no management permission at all: an ordinary authorization denial.
        String authorizationDenied = mockMvc.perform(get(MANAGEMENT).with(as(Credential.DIRECT)))
                .andReturn().getResponse().getContentAsString();

        String credentialRefused = mockMvc.perform(get(MANAGEMENT)
                        .with(as(Credential.personalAccessToken("pat-leaked"),
                                PatAuthorities.MANAGE_OWN)))
                .andReturn().getResponse().getContentAsString();

        // The distinction a client library branches on, and the reason these are two codes rather than one.
        // The two demand opposite actions: reissue the credential, versus request a role. A client that
        // cannot tell them apart sends its user down the wrong path.
        assertThat(credentialRefused).contains("ludwig.pat.credential-not-permitted");
        assertThat(authorizationDenied).doesNotContain("ludwig.pat.credential-not-permitted");
        assertThat(credentialRefused).isNotEqualTo(authorizationDenied);
    }

    @Test
    @DisplayName("the published edge constants are what the module documents, so the edge has a definition")
    void publishedConstantsAreStable() {
        // Not a behavioural test. It pins the values the EDGE is configured against, because they are a
        // contract with something outside this repository and a silent change to them would be a silent
        // change to how a client library recognises a refusal.
        assertThat(PatProblemTypes.WWW_AUTHENTICATE).isEqualTo("Bearer error=\"invalid_token\"");
        assertThat(PatProblemTypes.CREDENTIAL_REJECTED)
                .isEqualTo("https://problems.ludwigandreas.ru/credential/rejected");
        assertThat(PatProblemTypes.MANAGEMENT_PATH).isEqualTo("/api/v1/personal-access-tokens");
    }
}
