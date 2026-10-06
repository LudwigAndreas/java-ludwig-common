package ru.ludwigandreas.pat.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.util.LinkedHashMap;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.ludwigandreas.pat.service.IssueTokenCommand;
import ru.ludwigandreas.pat.service.PatService;
import ru.ludwigandreas.pat.service.RevocationReasons;
import ru.ludwigandreas.pat.token.PatTokens;
import ru.ludwigandreas.security.principal.LudwigAuthentication;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;
import ru.ludwigandreas.testsupport.image.LudwigTestImages;

/**
 * Every introspection failure, compared byte for byte.
 *
 * <p>The sibling of {@code UniformExchangeFailureIT}, and necessary separately rather than implied by it:
 * the two endpoints share {@code PatVerifier} but not their rendering. The exchange throws and lets the
 * {@code ProblemDetail} pipeline answer; introspection returns a {@code 200} with {@code active: false},
 * which is RFC 7662's model. So the no-oracle property has to be proven twice because it is implemented
 * twice.
 *
 * <p>The pairing is the point, as it is on the exchange: the bodies must be <b>identical</b> and the
 * reasons must be <b>distinguishable in metrics</b>. Identical bodies with no telemetry leaves the
 * defender blind; good telemetry with varying bodies is the oracle.
 */
@SpringBootTest(classes = ExchangeTestSupport.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class UniformIntrospectionFailureIT {

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
    private ExchangeTestSupport.RecordingMetrics metrics;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor asCallingService() {
        LudwigPrincipal principal = LudwigPrincipal.builder()
                .subject("spiffe://cluster/ns/deploy/sa/deploy-service")
                .type(PrincipalType.SERVICE)
                .build();
        return org.springframework.security.test.web.servlet.request
                .SecurityMockMvcRequestPostProcessors.authentication(
                        new LudwigAuthentication(principal));
    }

    private String issue(Duration lifetime, Set<String> audiences) {
        return service.issue(
                new IssueTokenCommand("alice", "ci", Set.of("orders:read"), audiences, lifetime, List.of()),
                "alice", Set.of()).rendered();
    }

    private MvcResult introspect(String token, String resource) throws Exception {
        var request = post("/introspect")
                .with(asCallingService())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token", token == null ? "" : token);
        if (resource != null) {
            request = request.param("resource", resource);
        }
        return mockMvc.perform(request).andReturn();
    }

    @Test
    @DisplayName("every cause returns a byte-identical body, and each is distinct in the metric tag")
    void everyFailureIsIdenticalAndStillDistinguishableInMetrics() throws Exception {
        Map<String, MvcResult> responses = new LinkedHashMap<>();
        String real = issue(Duration.ofDays(30), Set.of("deploy-service"));

        metrics.clear();
        responses.put("unknown-key", introspect(PatTokens.mint().rendered(), "deploy-service"));
        assertThat(metrics.failures()).containsExactly("unknown-key");

        metrics.clear();
        responses.put("bad-secret", introspect(swapSecret(real), "deploy-service"));
        assertThat(metrics.failures()).containsExactly("bad-secret");

        metrics.clear();
        responses.put("malformed", introspect("not-a-token-at-all", "deploy-service"));
        assertThat(metrics.failures()).containsExactly("malformed");

        var revoked = service.issue(
                new IssueTokenCommand("alice", "ci", Set.of("orders:read"),
                        Set.of("deploy-service"), Duration.ofDays(30), List.of()),
                "alice", Set.of());
        service.revoke(revoked.token().id(), "operator", RevocationReasons.COMPROMISED);
        metrics.clear();
        responses.put("revoked", introspect(revoked.rendered(), "deploy-service"));
        assertThat(metrics.failures()).containsExactly("revoked");

        metrics.clear();
        responses.put("expired", introspect(
                issue(Duration.ofSeconds(-60), Set.of("deploy-service")), "deploy-service"));
        assertThat(metrics.failures()).containsExactly("expired");

        metrics.clear();
        responses.put("audience-not-permitted", introspect(real, "billing-service"));
        assertThat(metrics.failures()).containsExactly("audience-not-permitted");

        metrics.clear();
        responses.put("audience-missing", introspect(real, null));
        assertThat(metrics.failures()).containsExactly("audience-missing");

        // Seven distinct causes, one response.
        assertThat(responses).hasSize(7);

        MvcResult reference = responses.values().iterator().next();
        String referenceBody = reference.getResponse().getContentAsString();
        int referenceStatus = reference.getResponse().getStatus();

        for (Map.Entry<String, MvcResult> entry : responses.entrySet()) {
            assertThat(entry.getValue().getResponse().getStatus())
                    .as("status for %s must be indistinguishable", entry.getKey())
                    .isEqualTo(referenceStatus);
            assertThat(entry.getValue().getResponse().getContentAsString())
                    .as("body for %s must be byte-for-byte indistinguishable - anything that"
                            + " distinguishes one cause from another is an oracle", entry.getKey())
                    .isEqualTo(referenceBody);
        }

        // 200, not 401: RFC 7662's model, where "this token is not usable" is a successful answer to a
        // valid question. A 401 would read as "your introspection call was unauthorized", which sends an
        // operator to the wrong place. No timestamp to normalise here, unlike the exchange's problem
        // document - which is why this comparison is exact rather than filtered.
        assertThat(referenceStatus).isEqualTo(200);
        assertThat(referenceBody).contains("\"active\":false");

        for (Map.Entry<String, MvcResult> entry : responses.entrySet()) {
            assertThat(entry.getValue().getResponse().getContentAsString())
                    .as("the response for %s must not carry that reason", entry.getKey())
                    .doesNotContain(entry.getKey());
        }
    }

    @Test
    @DisplayName("the inactive body carries no field that could identify the token")
    void inactiveBodyIdentifiesNothing() throws Exception {
        String body = introspect(PatTokens.mint().rendered(), "deploy-service")
                .getResponse().getContentAsString();

        // Not even the token id. An attacker who guessed a key id and got an id back would have
        // confirmed the key id exists, which is exactly the oracle the uniform response denies.
        assertThat(body)
                .doesNotContain("patId")
                .doesNotContain("sub")
                .doesNotContain("scope")
                .doesNotContain("aud")
                .doesNotContain("exp");
    }

    /** The same key id, a different secret - so the lookup succeeds and the digest comparison fails. */
    private static String swapSecret(String token) {
        String[] parts = token.split("_");
        String[] other = PatTokens.mint().rendered().split("_");
        String body = parts[0] + "_" + parts[1] + "_" + other[2];
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return body + "_" + Long.toHexString(crc.getValue());
    }
}
