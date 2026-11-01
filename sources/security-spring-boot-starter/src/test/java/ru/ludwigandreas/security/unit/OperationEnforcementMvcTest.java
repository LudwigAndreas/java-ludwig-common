package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.security.operation.Operation;
import ru.ludwigandreas.security.operation.OperationContributor;
import ru.ludwigandreas.security.operation.OperationEvaluator;
import ru.ludwigandreas.security.operation.RequiresOperation;
import ru.ludwigandreas.security.operation.Sensitivity;
import ru.ludwigandreas.security.principal.Credential;
import ru.ludwigandreas.security.principal.PrincipalType;

/**
 * {@code @RequiresOperation} on the service's own API, enforced by method security from the same role table the
 * administrative surface reads, and the holders endpoint that answers who may do what.
 */
class OperationEnforcementMvcTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A service's own API: a read, a change and a privileged change. */
    @RestController
    static class CatalogController {

        @GetMapping("/v1/things")
        @RequiresOperation("fixture.catalog.read")
        public String read() {
            return "things";
        }

        @PostMapping("/v1/things/purge")
        @RequiresOperation("fixture.catalog.purge")
        public String purge() {
            return "purged";
        }
    }

    static class BusinessApplication extends AdminSurfaceMvcTest.FixtureApplication {

        @Bean
        OperationContributor businessOperations() {
            return () -> List.of(
                    Operation.business("fixture.catalog.read", Sensitivity.READ, "test.fixture.read"),
                    Operation.business("fixture.catalog.purge", Sensitivity.PRIVILEGED, "test.fixture.privileged"));
        }

        @Bean
        CatalogController catalogController() {
            return new CatalogController();
        }
    }

    @Nested
    @SpringBootTest(classes = BusinessApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
            properties = {
                "ludwig.security.jwt.enabled=false", "ludwig.web.i18n.configure-validator=false",
                "ludwig.security.roles.CATALOG_READER.operations[fixture.catalog.read]=ALL",
                "ludwig.security.roles.CATALOG_ADMIN.includes[0]=CATALOG_READER",
                "ludwig.security.roles.CATALOG_ADMIN.operations[fixture.catalog.purge]=ALL",
                "ludwig.security.roles.NOTIFICATION_ADMIN.operations[security.operation.read]=ALL"})
    @AutoConfigureMockMvc
    class Business {

        @Autowired
        private MockMvc mvc;

        @Test
        @DisplayName("a business call is served to a holder and refused with 403 to anyone else")
        void heldAndNotHeld() throws Exception {
            assertThat(mvc.perform(get("/v1/things").with(AdminSurfaceMvcTest.as(PrincipalType.USER,
                    Credential.DIRECT, "CATALOG_READER"))).andReturn().getResponse().getStatus()).isEqualTo(200);
            assertThat(mvc.perform(get("/v1/things").with(AdminSurfaceMvcTest.as(PrincipalType.USER,
                    Credential.DIRECT, "SOMETHING_ELSE"))).andReturn().getResponse().getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("a privileged business call needs Operation-Reason, after the grant is established")
        void privilegedNeedsAReason() throws Exception {
            MvcResult reader = mvc.perform(post("/v1/things/purge").with(AdminSurfaceMvcTest.as(PrincipalType.USER,
                    Credential.DIRECT, "CATALOG_READER"))).andReturn();
            MvcResult noReason = mvc.perform(post("/v1/things/purge").with(AdminSurfaceMvcTest.as(PrincipalType.USER,
                    Credential.DIRECT, "CATALOG_ADMIN"))).andReturn();
            MvcResult withReason = mvc.perform(post("/v1/things/purge")
                    .header(OperationEvaluator.REASON_HEADER, "INC-1")
                    .with(AdminSurfaceMvcTest.as(PrincipalType.USER, Credential.DIRECT, "CATALOG_ADMIN"))).andReturn();

            assertThat(reader.getResponse().getStatus()).isEqualTo(403);
            assertThat(noReason.getResponse().getStatus()).isEqualTo(400);
            assertThat(AdminSurfaceMvcTest.problem(noReason).path("code").asText())
                    .isEqualTo("ludwig.security.reason-required");
            assertThat(withReason.getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("discovery lists business and administrative operations alike, each with its surface")
        void discoveryCoversEverySurface() throws Exception {
            JsonNode held = JSON.readTree(mvc.perform(get("/me/operations").with(AdminSurfaceMvcTest.as(
                    PrincipalType.USER, Credential.DIRECT, "CATALOG_ADMIN"))).andReturn().getResponse()
                    .getContentAsString());

            assertThat(held.findValuesAsText("id")).containsExactly("fixture.catalog.purge", "fixture.catalog.read");
            assertThat(held.findValuesAsText("surface")).containsOnly("BUSINESS");
        }

        @Test
        @DisplayName("the holders endpoint lists each holding role, with the role it was included from")
        void holders() throws Exception {
            MvcResult result = mvc.perform(get("/admin/v1/security/operations/fixture.catalog.read/holders")
                    .with(AdminSurfaceMvcTest.as(PrincipalType.USER, Credential.DIRECT, "NOTIFICATION_ADMIN")))
                    .andReturn();
            MvcResult refused = mvc.perform(get("/admin/v1/security/operations/fixture.catalog.read/holders")
                    .with(AdminSurfaceMvcTest.as(PrincipalType.USER, Credential.DIRECT, "CATALOG_ADMIN")))
                    .andReturn();

            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString())
                    .isEqualTo(200);
            JsonNode holders = JSON.readTree(result.getResponse().getContentAsString());
            assertThat(holders.findValuesAsText("role")).containsExactlyInAnyOrder("ROLE_CATALOG_ADMIN",
                    "ROLE_CATALOG_READER");
            assertThat(holders.findValuesAsText("via")).contains("ROLE_CATALOG_READER");
        }
    }

    /** An administrative operation declared on a path outside the administrative surface. */
    @RestController
    static class MisplacedController {

        @GetMapping("/v1/fixture/things")
        @RequiresOperation("fixture.thing.read")
        public String things() {
            return "things";
        }
    }

    static class MisplacedApplication extends AdminSurfaceMvcTest.FixtureApplication {

        @Bean
        MisplacedController misplacedController() {
            return new MisplacedController();
        }
    }

    @Test
    @DisplayName("an administrative operation on a path outside /admin/v1/ fails startup, naming it")
    void surfaceMismatchFailsStartup() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(MisplacedApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties("ludwig.security.jwt.enabled=false", "ludwig.web.i18n.configure-validator=false",
                        "server.port=0")
                .run().close())
                .hasStackTraceContaining("fixture.thing.read")
                .hasStackTraceContaining("only there");
    }
}
