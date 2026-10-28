package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.security.admin.AdminAccess;
import ru.ludwigandreas.security.admin.AdminAudit;
import ru.ludwigandreas.security.admin.AdminInvocation;
import ru.ludwigandreas.security.admin.AdminOperation;
import ru.ludwigandreas.security.admin.AdminOperationContributor;
import ru.ludwigandreas.security.admin.AdminOutcome;
import ru.ludwigandreas.security.principal.Credential;
import ru.ludwigandreas.security.principal.LudwigAuthentication;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;

/**
 * The administrative surface through real MVC dispatch and web-core's problem pipeline: what a caller actually
 * receives, rather than what the guard throws.
 *
 * <p>A context test with no external infrastructure, so it lives with the unit tests as
 * {@code SecurityAutoConfigurationTest} does; this module binds no failsafe execution, and an {@code *IT} here would
 * never run.
 */
class AdminSurfaceMvcTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A module's administrative controller, as change two will write them. */
    @RestController
    static class FixtureAdminController {

        private final ObjectProvider<AdminAudit> audit;

        FixtureAdminController(ObjectProvider<AdminAudit> audit) {
            this.audit = audit;
        }

        @GetMapping("/admin/v1/fixture/things")
        @AdminAccess("fixture.read")
        public List<String> things() {
            return List.of("a", "b");
        }

        @PostMapping("/admin/v1/fixture/things/{id}/discard")
        @AdminAccess("fixture.privileged")
        public String discard(@PathVariable String id) {
            audit.getObject().record("fixture-thing", id, AdminOutcome.SUCCEEDED);
            return AdminInvocation.require().reason();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class FixtureApplication {

        @Bean
        AdminOperationContributor fixtureOperations() {
            return () -> List.of(
                    AdminOperation.of("fixture.read", "test.fixture.read"),
                    AdminOperation.of("fixture.operate", "test.fixture.operate"),
                    AdminOperation.of("fixture.privileged", "test.fixture.privileged"));
        }

        @Bean
        ProblemMessageBundle adminFixtureMessages() {
            return ProblemMessageBundle.of("i18n/admin-fixture-messages");
        }

        @Bean
        FixtureAdminController fixtureAdminController(ObjectProvider<AdminAudit> audit) {
            return new FixtureAdminController(audit);
        }
    }

    static RequestPostProcessor as(PrincipalType type, Credential credential, String... roles) {
        LudwigPrincipal principal = LudwigPrincipal.builder()
                .subject("alice").type(type).roles(List.of(roles)).build();
        return authentication(new LudwigAuthentication(principal, credential));
    }

    static JsonNode problem(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    @Nested
    @SpringBootTest(classes = FixtureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
            properties = {
                "ludwig.security.jwt.enabled=false", "ludwig.web.i18n.configure-validator=false",
                "ludwig.admin.grants.fixture.read[0]=NOTIFICATION_ADMIN",
                "ludwig.admin.grants.fixture.read[1]=NOTIFICATION_SUPPORT",
                "ludwig.admin.grants.fixture.privileged[0]=NOTIFICATION_ADMIN"})
    @AutoConfigureMockMvc
    class PlatformChain {

        @Autowired
        private MockMvc mvc;

        @Test
        @DisplayName("an unauthenticated request is a 401 and reaches no handler")
        void unauthenticated() throws Exception {
            MvcResult result = mvc.perform(get("/admin/v1/fixture/things")).andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(401);
        }

        @Test
        @DisplayName("a granted read is served; an ungranted caller is a 403 problem document")
        void grantedAndUngranted() throws Exception {
            MvcResult granted = mvc.perform(get("/admin/v1/fixture/things")
                    .with(as(PrincipalType.USER, Credential.DIRECT, "NOTIFICATION_SUPPORT"))).andReturn();
            MvcResult refused = mvc.perform(get("/admin/v1/fixture/things")
                    .with(as(PrincipalType.USER, Credential.DIRECT, "SOMETHING_ELSE"))).andReturn();

            assertThat(granted.getResponse().getStatus()).isEqualTo(200);
            assertThat(granted.getResponse().getContentAsString()).isEqualTo("[\"a\",\"b\"]");
            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            assertThat(problem(refused).path("code").asText()).isEqualTo("ludwig.security.error.forbidden");
        }

        @Test
        @DisplayName("a partner is a 403 whatever it holds")
        void partner() throws Exception {
            MvcResult result = mvc.perform(get("/admin/v1/fixture/things")
                    .with(as(PrincipalType.PARTNER, Credential.DIRECT, "NOTIFICATION_ADMIN"))).andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("a PAT caller gets the credential-not-permitted problem, the PAT surface's own type")
        void personalAccessToken() throws Exception {
            MvcResult result = mvc.perform(get("/admin/v1/fixture/things")
                    .with(as(PrincipalType.USER, Credential.personalAccessToken("p1"), "NOTIFICATION_ADMIN")))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            JsonNode body = problem(result);
            assertThat(body.path("code").asText()).isEqualTo("ludwig.pat.credential-not-permitted");
            assertThat(body.path("type").asText()).isEqualTo("urn:ludwig:problem:ludwig.pat.credential-not-permitted");
        }

        @Test
        @DisplayName("a privileged call: 403 without the grant, 400 without a reason, served with one")
        void privileged() throws Exception {
            MvcResult ungranted = mvc.perform(post("/admin/v1/fixture/things/t1/discard")
                    .with(as(PrincipalType.USER, Credential.DIRECT, "NOTIFICATION_SUPPORT"))).andReturn();
            MvcResult noReason = mvc.perform(post("/admin/v1/fixture/things/t1/discard")
                    .with(as(PrincipalType.USER, Credential.DIRECT, "NOTIFICATION_ADMIN"))).andReturn();
            MvcResult served = mvc.perform(post("/admin/v1/fixture/things/t1/discard")
                    .header(AdminInvocation.REASON_HEADER, "INC-4411")
                    .with(as(PrincipalType.USER, Credential.DIRECT, "NOTIFICATION_ADMIN"))).andReturn();

            assertThat(ungranted.getResponse().getStatus()).isEqualTo(403);
            assertThat(noReason.getResponse().getStatus()).isEqualTo(400);
            assertThat(problem(noReason).path("code").asText()).isEqualTo("ludwig.admin.reason-required");
            assertThat(served.getResponse().getStatus()).isEqualTo(200);
            assertThat(served.getResponse().getContentAsString()).isEqualTo("INC-4411");
        }

        @Test
        @DisplayName("discovery lists only what the caller holds, and an empty list for a caller holding nothing")
        void discovery() throws Exception {
            MvcResult support = mvc.perform(get("/admin/v1/me/operations")
                    .with(as(PrincipalType.USER, Credential.DIRECT, "NOTIFICATION_SUPPORT"))).andReturn();
            MvcResult nobody = mvc.perform(get("/admin/v1/me/operations")
                    .with(as(PrincipalType.USER, Credential.DIRECT))).andReturn();

            assertThat(support.getResponse().getStatus()).isEqualTo(200);
            assertThat(support.getResponse().getContentType()).startsWith("application/json");
            JsonNode held = JSON.readTree(support.getResponse().getContentAsString());
            assertThat(held).hasSize(1);
            assertThat(held.get(0).path("id").asText()).isEqualTo("fixture.read");
            assertThat(held.get(0).path("tier").asText()).isEqualTo("read");
            assertThat(nobody.getResponse().getContentAsString()).isEqualTo("[]");
        }
    }

    /** A service that replaced the platform's chain with one that lets everything through. */
    @Import(OpenChain.Chain.class)
    static class OpenChainApplication extends FixtureApplication {
    }

    @Nested
    @SpringBootTest(classes = OpenChainApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
            properties = {
                "ludwig.security.jwt.enabled=false", "ludwig.web.i18n.configure-validator=false",
                "ludwig.admin.grants.fixture.read[0]=NOTIFICATION_ADMIN"})
    @AutoConfigureMockMvc
    class OpenChain {

        static class Chain {
            @Bean
            SecurityFilterChain permitEverything(HttpSecurity http) throws Exception {
                return http.csrf(csrf -> csrf.disable())
                        .authorizeHttpRequests(requests -> requests.anyRequest().permitAll()).build();
            }
        }

        @Autowired
        private MockMvc mvc;

        @Test
        @DisplayName("an anonymous request is still a 401 when the service's own chain permits /admin/**")
        void stillRefused() throws Exception {
            MvcResult result = mvc.perform(get("/admin/v1/fixture/things")).andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            assertThat(result.getResponse().getContentAsString()).doesNotContain("\"a\"");
        }
    }

    @Nested
    @SpringBootTest(classes = FixtureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
            properties = {"ludwig.security.jwt.enabled=false", "ludwig.web.i18n.configure-validator=false",
                "ludwig.admin.enabled=false"})
    @AutoConfigureMockMvc
    class SwitchedOff {

        @Autowired
        private MockMvc mvc;

        @Autowired
        private org.springframework.context.ApplicationContext context;

        @Test
        @DisplayName("with the surface off there is no guard, no catalog and no discovery endpoint")
        void absent() throws Exception {
            assertThat(context.getBeanNamesForType(ru.ludwigandreas.security.admin.AdminSurfaceGuard.class)).isEmpty();
            assertThat(context.getBeanNamesForType(ru.ludwigandreas.security.admin.AdminOperationCatalog.class))
                    .isEmpty();
            MvcResult result = mvc.perform(get("/admin/v1/me/operations")
                    .with(as(PrincipalType.USER, Credential.DIRECT, "NOTIFICATION_ADMIN"))).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(404);
        }
    }
}
