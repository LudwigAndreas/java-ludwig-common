package ru.ludwigandreas.pat.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.pat.repository.PatRepository;
import ru.ludwigandreas.pat.service.PatService;
import ru.ludwigandreas.security.admin.AdminInvocation;
import ru.ludwigandreas.security.principal.Credential;
import ru.ludwigandreas.security.principal.LudwigAuthentication;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;
import ru.ludwigandreas.testsupport.image.LudwigTestImages;

/**
 * The {@code pat} area through MVC dispatch, the administrative guard and the module's credential guard, over a
 * real token table.
 *
 * <p>The surface is opened to personal access tokens here on purpose: the last case shows this area refuses them
 * anyway.
 */
@SpringBootTest(classes = ExchangeTestSupport.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "ludwig.admin.allow-personal-access-tokens=true",
            "ludwig.admin.grants.pat.read[0]=PAT_SUPPORT",
            "ludwig.admin.grants.pat.read[1]=PAT_ADMIN",
            "ludwig.admin.grants.pat.privileged[0]=PAT_ADMIN"})
@AutoConfigureMockMvc
@Testcontainers
class PatAdminIT {

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

    private static final String TOKENS = "/admin/v1/pat/tokens";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PatService service;

    @Autowired
    private PatRepository repository;

    @Autowired
    private ExchangeTestSupport.RecordingSink auditSink;

    @BeforeEach
    void reset() {
        repository.deleteAll();
        auditSink.clear();
    }

    private static RequestPostProcessor as(String subject, String role, Credential credential) {
        LudwigPrincipal principal = LudwigPrincipal.builder()
                .subject(subject)
                .type(PrincipalType.USER)
                .roles(List.of(role))
                .build();
        return SecurityMockMvcRequestPostProcessors.authentication(credential == null
                ? new LudwigAuthentication(principal) : new LudwigAuthentication(principal, credential));
    }

    private static RequestPostProcessor admin() {
        return as("olga", "PAT_ADMIN", null);
    }

    private static String issueFor(String owner) throws Exception {
        return JSON.writeValueAsString(Map.of(
                "owner", owner,
                "name", "ci-deploy",
                "scopes", List.of("orders:read"),
                "audiences", List.of("deploy-service"),
                "lifetime", "P30D"));
    }

    @Test
    @DisplayName("an administrator issues for bob: the secret is returned once and the reason is audited")
    void issuesForAnotherOwner() throws Exception {
        MvcResult issued = mvc.perform(post(TOKENS).contentType(MediaType.APPLICATION_JSON).content(issueFor("bob"))
                        .header(AdminInvocation.REASON_HEADER, "pipeline handover").with(admin()))
                .andReturn();

        assertThat(issued.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = JSON.readTree(issued.getResponse().getContentAsString());
        String secret = body.path("secret").asText();
        String id = body.path("token").path("id").asText();
        assertThat(secret).startsWith("lpat_");
        assertThat(body.path("token").path("owner").asText()).isEqualTo("bob");

        String reread = mvc.perform(get(TOKENS + "/" + id).with(as("sam", "PAT_SUPPORT", null)))
                .andReturn().getResponse().getContentAsString();
        String listed = mvc.perform(get(TOKENS).param("$filter", "ownerSubject eq 'bob'")
                        .with(as("sam", "PAT_SUPPORT", null)))
                .andReturn().getResponse().getContentAsString();
        assertThat(reread).doesNotContain(secret).doesNotContain("lpat_").doesNotContain("digest");
        assertThat(JSON.readTree(listed).path("items")).hasSize(1);

        AuditEvent admin = auditSink.lastOf("pat.privileged");
        assertThat(admin.attributes()).containsEntry("owner", "bob");
        assertThat(admin.toString()).contains("pipeline handover");
        assertThat(auditSink.lastOf("pat.issued").attributes()).containsEntry("issuedBy", "olga");
    }

    @Test
    @DisplayName("a privileged issue without a reason is 400 and mints nothing")
    void reasonRequired() throws Exception {
        int status = mvc.perform(post(TOKENS).contentType(MediaType.APPLICATION_JSON).content(issueFor("bob"))
                .with(admin())).andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(400);
        assertThat(service.list("bob")).isEmpty();
    }

    @Test
    @DisplayName("an administrator revokes bob's token; it is no longer live, and status filters see it")
    void revokesAnotherOwnersToken() throws Exception {
        String id = JSON.readTree(mvc.perform(post(TOKENS).contentType(MediaType.APPLICATION_JSON)
                        .content(issueFor("bob")).header(AdminInvocation.REASON_HEADER, "setup").with(admin()))
                .andReturn().getResponse().getContentAsString()).path("token").path("id").asText();

        MvcResult revoked = mvc.perform(post(TOKENS + "/" + id + "/revoke")
                .header(AdminInvocation.REASON_HEADER, "leaked in a public repository").with(admin())).andReturn();

        assertThat(revoked.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(revoked.getResponse().getContentAsString()).path("live").asBoolean()).isFalse();
        assertThat(auditSink.lastOf("pat.revoked").attributes()).containsEntry("revokedBy", "olga");
        String active = mvc.perform(get(TOKENS).param("$filter", "ownerSubject eq 'bob' and revokedAt eq null")
                .with(admin())).andReturn().getResponse().getContentAsString();
        String revokedOnes = mvc.perform(get(TOKENS).param("$filter", "ownerSubject eq 'bob' and revokedAt ne null")
                .with(admin())).andReturn().getResponse().getContentAsString();
        assertThat(JSON.readTree(active).path("items")).isEmpty();
        assertThat(JSON.readTree(revokedOnes).path("items")).hasSize(1);
        for (String secretField : java.util.List.of("secretDigest", "keyId", "lastUsedIp", "name")) {
            assertThat(mvc.perform(get(TOKENS).param("$filter", secretField + " eq 'x'").with(admin())).andReturn()
                    .getResponse().getStatus()).as(secretField).isEqualTo(400);
        }
    }

    @Test
    @DisplayName("read alone may not issue")
    void readMayNotIssue() throws Exception {
        int status = mvc.perform(post(TOKENS).contentType(MediaType.APPLICATION_JSON).content(issueFor("bob"))
                        .header(AdminInvocation.REASON_HEADER, "x").with(as("sam", "PAT_SUPPORT", null)))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(403);
    }

    @Test
    @DisplayName("a PAT-credentialed caller is refused even with the surface opened to PATs")
    void personalAccessTokenRefused() throws Exception {
        MvcResult result = mvc.perform(post(TOKENS).contentType(MediaType.APPLICATION_JSON).content(issueFor("bob"))
                        .header(AdminInvocation.REASON_HEADER, "x")
                        .with(as("olga", "PAT_ADMIN", Credential.personalAccessToken("pat-leaked"))))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString()).contains("credential-not-permitted");
        assertThat(service.list("bob")).isEmpty();
    }
}
