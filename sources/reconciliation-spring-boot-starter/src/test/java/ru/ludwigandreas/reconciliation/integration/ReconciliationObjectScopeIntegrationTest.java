package ru.ludwigandreas.reconciliation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static ru.ludwigandreas.reconciliation.integration.EnumerationFixture.item;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ru.ludwigandreas.reconciliation.integration.testmodel.ItemCatalogueEnumeration;
import ru.ludwigandreas.reconciliation.integration.testmodel.PageTreeEnumeration;
import ru.ludwigandreas.reconciliation.integration.testmodel.StubCollectionPartner;
import ru.ludwigandreas.security.data.DataScope;
import ru.ludwigandreas.security.data.DataScopeProvider;
import ru.ludwigandreas.security.data.ScopeDimension;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;
import ru.ludwigandreas.testsupport.security.TestPrincipalBuilder;

/**
 * Reconciliation tasks as scoped administrative objects: the supply team's role is narrowed to the task it is granted
 * per subject, while the platform admin's role keeps every task. Without a policy entry nothing is scoped, which
 * {@code ReconciliationAdminIntegrationTest} shows by running unchanged.
 */
@SpringBootTest(classes = ReconciliationTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "ludwig.security.enabled=true",
            "ludwig.security.jwt.enabled=false",
            "ludwig.admin.grants.reconciliation.read[0]=SUPPLY_OPS",
            "ludwig.admin.grants.reconciliation.read[1]=SYNC_ADMIN",
            "ludwig.admin.grants.reconciliation.operate[0]=SUPPLY_OPS",
            "ludwig.admin.grants.reconciliation.operate[1]=SYNC_ADMIN",
            "ludwig.admin.grants.reconciliation.privileged[0]=SYNC_ADMIN",
            "ludwig.security.data.policies[admin.reconciliation].read.ROLE_SUPPLY_OPS=NONE",
            "ludwig.security.data.policies[admin.reconciliation].read.ROLE_SYNC_ADMIN=ALL",
            "ludwig.security.data.policies[admin.reconciliation].operate.ROLE_SUPPLY_OPS=NONE",
            "ludwig.security.data.policies[admin.reconciliation].operate.ROLE_SYNC_ADMIN=ALL"})
@Import({PostgresContainerConfiguration.class, ReconciliationObjectScopeIntegrationTest.BobsGrants.class})
@AutoConfigureMockMvc
class ReconciliationObjectScopeIntegrationTest {

    private static final String OWN = ItemCatalogueEnumeration.NAME;
    private static final String OTHER = PageTreeEnumeration.NAME;
    private static final String BASE = "/admin/v1/reconciliation/tasks/";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** What the identity projection's per-subject grants would give bob: the one task his team owns. */
    @TestConfiguration
    static class BobsGrants {

        @Bean
        DataScopeProvider bobsTaskGrants() {
            return (principal, resource, action) ->
                    "bob".equals(principal.subject()) && "admin.reconciliation".equals(resource)
                    ? DataScope.restrictedTo(ScopeDimension.OBJECT, OWN)
                    : DataScope.none();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private EnumerationFixture fixture;

    @Autowired
    private StubCollectionPartner partner;

    @BeforeEach
    void resetState() {
        fixture.reset();
    }

    private static RequestPostProcessor bob() {
        return TestPrincipalBuilder.user("bob").roles("SUPPLY_OPS").postProcessor();
    }

    private static RequestPostProcessor admin() {
        return TestPrincipalBuilder.user("alice").roles("SYNC_ADMIN").postProcessor();
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn();
    }

    private JsonNode json(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = perform(request);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isLessThan(300);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("bob operates his own task, and another task is a 403")
    void ownTaskOnly() throws Exception {
        partner.lists(ItemCatalogueEnumeration.SCOPE, item("I-1", "one"));

        assertThat(perform(post(BASE + OWN + "/discover").with(bob())).getResponse().getStatus()).isEqualTo(200);
        assertThat(perform(post(BASE + OTHER + "/discover").with(bob())).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("another task read by bob is a 404 identical to a task that does not exist")
    void otherTaskIsNotDisclosed() throws Exception {
        MvcResult hidden = perform(get(BASE + OTHER).with(bob()));
        MvcResult missing = perform(get(BASE + "no-such-task").with(admin()));

        assertThat(hidden.getResponse().getStatus()).isEqualTo(404);
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        JsonNode hiddenProblem = JSON.readTree(hidden.getResponse().getContentAsString());
        JsonNode missingProblem = JSON.readTree(missing.getResponse().getContentAsString());
        for (String field : List.of("type", "title", "status", "code")) {
            assertThat(hiddenProblem.path(field)).isEqualTo(missingProblem.path(field));
        }
    }

    @Test
    @DisplayName("the listing shows bob his task and no other; the platform admin still sees every task")
    void listingIsScoped() throws Exception {
        JsonNode bobs = json(get("/admin/v1/reconciliation/tasks").with(bob()));
        JsonNode admins = json(get("/admin/v1/reconciliation/tasks").with(admin()));

        assertThat(bobs.path("enumerations").findValuesAsText("name")).containsExactly(OWN);
        assertThat(bobs.path("tasks").findValuesAsText("name")).isEmpty();
        assertThat(admins.path("enumerations").findValuesAsText("name")).contains(OWN, OTHER);
        assertThat(admins.path("tasks").findValuesAsText("name")).contains("billing-status");
    }
}
