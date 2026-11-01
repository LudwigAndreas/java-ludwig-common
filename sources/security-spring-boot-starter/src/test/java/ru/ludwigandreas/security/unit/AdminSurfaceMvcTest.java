package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.builder.SpringApplicationBuilder;
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
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditOutcome;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.security.admin.AdminAudit;
import ru.ludwigandreas.security.admin.AdminInvocation;
import ru.ludwigandreas.security.admin.AdminObject;
import ru.ludwigandreas.security.admin.AdminObjectNotFoundException;
import ru.ludwigandreas.security.admin.AdminOutcome;
import ru.ludwigandreas.security.operation.Operation;
import ru.ludwigandreas.security.operation.OperationContributor;
import ru.ludwigandreas.security.operation.OperationEvaluator;
import ru.ludwigandreas.security.operation.RequiresOperation;
import ru.ludwigandreas.security.operation.Sensitivity;
import ru.ludwigandreas.security.data.DataScope;
import ru.ludwigandreas.security.data.DataScopeProvider;
import ru.ludwigandreas.security.data.ScopeDimension;
import ru.ludwigandreas.security.principal.Credential;
import ru.ludwigandreas.security.principal.Delegation;
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
        @RequiresOperation("fixture.thing.read")
        public List<String> things() {
            return List.of("a", "b");
        }

        @GetMapping("/admin/v1/fixture/things/{name}")
        @RequiresOperation("fixture.thing.read")
        public String thing(@PathVariable @AdminObject(notFound = "test.fixture.thing-not-found") String name) {
            if (!List.of("a", "b").contains(name)) {
                throw new AdminObjectNotFoundException("test.fixture.thing-not-found", name);
            }
            return name;
        }

        @PostMapping("/admin/v1/fixture/things/{thing}/poke")
        @RequiresOperation("fixture.thing.poke")
        public String poke(@PathVariable("thing") @AdminObject String name) {
            return "poked " + name;
        }

        @PostMapping("/admin/v1/fixture/things/{id}/discard")
        @RequiresOperation("fixture.thing.discard")
        public String discard(@PathVariable String id) {
            audit.getObject().record("fixture-thing", id, AdminOutcome.SUCCEEDED);
            return AdminInvocation.require().reason();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class FixtureApplication {

        @Bean
        OperationContributor fixtureOperations() {
            return () -> List.of(
                    Operation.admin("fixture.thing.read", Sensitivity.READ, "test.fixture.read"),
                    Operation.admin("fixture.thing.poke", Sensitivity.CHANGE, "test.fixture.operate"),
                    Operation.admin("fixture.thing.discard", Sensitivity.PRIVILEGED, "test.fixture.privileged"));
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
                "ludwig.security.roles.NOTIFICATION_ADMIN.operations[fixture.thing.read]=ALL",
                "ludwig.security.roles.NOTIFICATION_SUPPORT.operations[fixture.thing.read]=ALL",
                "ludwig.security.roles.NOTIFICATION_ADMIN.operations[fixture.thing.discard]=ALL"})
    @AutoConfigureMockMvc
    class PlatformChain {

        @Autowired
        private MockMvc mvc;

        @Test
        @DisplayName("an OData paging or counting option is a 400 on an administrative endpoint; $filter is not")
        void offsetOptionsRefused() throws Exception {
            for (String option : List.of("$skip", "$top", "$orderby", "$count")) {
                MvcResult result = mvc.perform(get("/admin/v1/fixture/things").param(option, "1")
                        .with(as(PrincipalType.USER, Credential.DIRECT, "NOTIFICATION_SUPPORT"))).andReturn();
                assertThat(result.getResponse().getStatus()).as(option).isEqualTo(400);
                assertThat(problem(result).path("code").asText()).isEqualTo("ludwig.admin.query-option-unsupported");
            }
            assertThat(mvc.perform(get("/admin/v1/fixture/things").param("$filter", "x eq 1")
                    .with(as(PrincipalType.USER, Credential.DIRECT, "NOTIFICATION_SUPPORT"))).andReturn()
                    .getResponse().getStatus()).isEqualTo(200);
        }

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
                    .header(OperationEvaluator.REASON_HEADER, "INC-4411")
                    .with(as(PrincipalType.USER, Credential.DIRECT, "NOTIFICATION_ADMIN"))).andReturn();

            assertThat(ungranted.getResponse().getStatus()).isEqualTo(403);
            assertThat(noReason.getResponse().getStatus()).isEqualTo(400);
            assertThat(problem(noReason).path("code").asText()).isEqualTo("ludwig.security.reason-required");
            assertThat(served.getResponse().getStatus()).isEqualTo(200);
            assertThat(served.getResponse().getContentAsString()).isEqualTo("INC-4411");
        }

        @Test
        @DisplayName("discovery lists only what the caller holds, and an empty list for a caller holding nothing")
        void discovery() throws Exception {
            MvcResult support = mvc.perform(get("/me/operations")
                    .with(as(PrincipalType.USER, Credential.DIRECT, "NOTIFICATION_SUPPORT"))).andReturn();
            MvcResult nobody = mvc.perform(get("/me/operations")
                    .with(as(PrincipalType.USER, Credential.DIRECT))).andReturn();

            assertThat(support.getResponse().getStatus()).isEqualTo(200);
            assertThat(support.getResponse().getContentType()).startsWith("application/json");
            JsonNode held = JSON.readTree(support.getResponse().getContentAsString());
            assertThat(held).hasSize(1);
            assertThat(held.get(0).path("id").asText()).isEqualTo("fixture.thing.read");
            assertThat(held.get(0).path("sensitivity").asText()).isEqualTo("READ");
            assertThat(held.get(0).path("surface").asText()).isEqualTo("ADMIN");
            assertThat(held.get(0).path("scope").get(0).asText()).isEqualTo("ALL");
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
                "ludwig.security.roles.NOTIFICATION_ADMIN.operations[fixture.thing.read]=ALL"})
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
        @DisplayName("with the surface off there is no guard, and an administrative handler still mapped is refused")
        void absent() throws Exception {
            assertThat(context.getBeanNamesForType(ru.ludwigandreas.security.admin.AdminSurfaceGuard.class)).isEmpty();
            MvcResult result = mvc.perform(get("/admin/v1/fixture/things")
                    .with(as(PrincipalType.USER, Credential.DIRECT, "NOTIFICATION_ADMIN"))).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(403);
        }
    }

    static final List<AuditEvent> AUDIT = new CopyOnWriteArrayList<>();

    static class RequiredViaApplication extends FixtureApplication {

        @Bean
        AuditSink capturingAuditSink() {
            return AUDIT::add;
        }
    }

    static RequestPostProcessor through(PrincipalType type, String via, String... roles) {
        LudwigPrincipal principal = LudwigPrincipal.builder()
                .subject("alice").type(type).roles(List.of(roles)).build();
        LudwigAuthentication direct = new LudwigAuthentication(principal);
        return authentication(via == null ? direct : direct.withDelegation(new Delegation(List.of(via))));
    }

    @Nested
    @SpringBootTest(classes = RequiredViaApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
            properties = {
                "ludwig.security.jwt.enabled=false", "ludwig.web.i18n.configure-validator=false",
                "ludwig.security.roles.NOTIFICATION_SUPPORT.operations[fixture.thing.read]=ALL",
                "ludwig.admin.human-callers.required-via[0]=admin-console"})
    @AutoConfigureMockMvc
    class RequiredVia {

        @Autowired
        private MockMvc mvc;

        @Test
        @DisplayName("a person through the administrative UI is served")
        void throughTheUi() throws Exception {
            assertThat(mvc.perform(get("/admin/v1/fixture/things")
                    .with(through(PrincipalType.USER, "admin-console", "NOTIFICATION_SUPPORT"))).andReturn()
                    .getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("a person calling directly, or through another party, is a 403 audited with no acting party")
        void direct() throws Exception {
            AUDIT.clear();

            MvcResult direct = mvc.perform(get("/admin/v1/fixture/things")
                    .with(through(PrincipalType.USER, null, "NOTIFICATION_SUPPORT"))).andReturn();
            MvcResult elsewhere = mvc.perform(get("/admin/v1/fixture/things")
                    .with(through(PrincipalType.USER, "curl-wrapper", "NOTIFICATION_SUPPORT"))).andReturn();

            assertThat(direct.getResponse().getStatus()).isEqualTo(403);
            assertThat(elsewhere.getResponse().getStatus()).isEqualTo(403);
            assertThat(AUDIT).filteredOn(event -> event.outcome().status() == AuditOutcome.Status.DENIED)
                    .hasSize(2).first().satisfies(event -> {
                        assertThat(event.actor().subject()).isEqualTo("alice");
                        assertThat(event.actor().via()).isNull();
                    });
        }

        @Test
        @DisplayName("a service principal without an acting party is unaffected")
        void automation() throws Exception {
            assertThat(mvc.perform(get("/admin/v1/fixture/things")
                    .with(through(PrincipalType.SERVICE, null, "NOTIFICATION_SUPPORT"))).andReturn()
                    .getResponse().getStatus()).isEqualTo(200);
        }
    }

    /** What the identity projection's per-subject grants would give bob: one object, for every fixture operation. */
    static class ObjectScopedApplication extends RequiredViaApplication {

        @Bean
        DataScopeProvider bobsGrants() {
            return (principal, resource, action) ->
                    "bob".equals(principal.subject()) && resource.startsWith("fixture.thing.")
                    ? DataScope.restrictedTo(ScopeDimension.OBJECT, "a")
                    : DataScope.none();
        }
    }

    static RequestPostProcessor bob() {
        return authentication(new LudwigAuthentication(LudwigPrincipal.builder()
                .subject("bob").type(PrincipalType.USER).roles(List.of("SUPPLY_OPS")).build()));
    }

    @Nested
    @SpringBootTest(classes = ObjectScopedApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
            properties = {
                "ludwig.security.jwt.enabled=false", "ludwig.web.i18n.configure-validator=false",
                "ludwig.security.roles.SUPPLY_OPS.operations[fixture.thing.read]=OBJECT",
                "ludwig.security.roles.PLATFORM_ADMIN.operations[fixture.thing.read]=ALL",
                "ludwig.security.roles.SUPPLY_OPS.operations[fixture.thing.poke]=OBJECT"})
    @AutoConfigureMockMvc
    class ObjectScoped {

        @Autowired
        private MockMvc mvc;

        @Test
        @DisplayName("bob operates the object he is granted, and another is a 403 audited as a denial")
        void ownObjectOnly() throws Exception {
            AUDIT.clear();
            MvcResult own = mvc.perform(post("/admin/v1/fixture/things/a/poke").with(bob())).andReturn();
            MvcResult other = mvc.perform(post("/admin/v1/fixture/things/b/poke").with(bob())).andReturn();

            assertThat(own.getResponse().getStatus()).isEqualTo(200);
            assertThat(own.getResponse().getContentAsString()).isEqualTo("poked a");
            assertThat(other.getResponse().getStatus()).isEqualTo(403);
            assertThat(AUDIT).filteredOn(event -> event.outcome().status() == AuditOutcome.Status.DENIED)
                    .singleElement().satisfies(event -> assertThat(event.actor().subject()).isEqualTo("bob"));
        }

        @Test
        @DisplayName("an object outside a read scope is a 404 identical to an object that does not exist")
        void outOfScopeReadIsNotFound() throws Exception {
            MvcResult own = mvc.perform(get("/admin/v1/fixture/things/a").with(bob())).andReturn();
            MvcResult hidden = mvc.perform(get("/admin/v1/fixture/things/b").with(bob())).andReturn();
            MvcResult missing = mvc.perform(get("/admin/v1/fixture/things/b-gone").with(as(PrincipalType.USER,
                    Credential.DIRECT, "PLATFORM_ADMIN"))).andReturn();

            assertThat(own.getResponse().getStatus()).isEqualTo(200);
            assertThat(hidden.getResponse().getStatus()).isEqualTo(404).isEqualTo(missing.getResponse().getStatus());
            JsonNode hiddenProblem = problem(hidden);
            JsonNode missingProblem = problem(missing);
            for (String field : List.of("type", "title", "status", "code")) {
                assertThat(hiddenProblem.path(field)).isEqualTo(missingProblem.path(field));
            }
            assertThat(hiddenProblem.path("detail").asText()).isEqualTo("No thing b exists.");
            assertThat(missingProblem.path("detail").asText()).isEqualTo("No thing b-gone exists.");
        }
    }

    @Nested
    @SpringBootTest(classes = ObjectScopedApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
            properties = {
                "ludwig.security.jwt.enabled=false", "ludwig.web.i18n.configure-validator=false",
                "ludwig.security.roles.SUPPLY_OPS.operations[fixture.thing.poke]=ALL"})
    @AutoConfigureMockMvc
    class NoObjectPolicy {

        @Autowired
        private MockMvc mvc;

        @Test
        @DisplayName("a role holding the operation for ALL admits every object")
        void everyObject() throws Exception {
            assertThat(mvc.perform(post("/admin/v1/fixture/things/b/poke").with(bob())).andReturn().getResponse()
                    .getStatus()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("a row dimension on an administrative operation that touches no rows fails startup, naming it")
    void rowDimensionWithoutRowsRefused() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(ObjectScopedApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties("ludwig.security.jwt.enabled=false", "ludwig.web.i18n.configure-validator=false",
                        "server.port=0",
                        "ludwig.security.roles.SUPPLY_OPS.operations[fixture.thing.poke]=OWN")
                .run().close())
                .hasStackTraceContaining("ROLE_SUPPLY_OPS -> fixture.thing.poke")
                .hasStackTraceContaining("touches no rows");
    }
}
