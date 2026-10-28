package ru.ludwigandreas.reconciliation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static ru.ludwigandreas.reconciliation.integration.EnumerationFixture.item;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.reconciliation.entity.ScopeMemberStatus;
import ru.ludwigandreas.reconciliation.entity.SyncScopeState;
import ru.ludwigandreas.reconciliation.integration.testmodel.ItemCatalogueEnumeration;
import ru.ludwigandreas.reconciliation.integration.testmodel.ItemReconciler;
import ru.ludwigandreas.reconciliation.integration.testmodel.RecordingAuditSink;
import ru.ludwigandreas.reconciliation.integration.testmodel.StubCollectionPartner;
import ru.ludwigandreas.security.admin.AdminInvocation;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;
import ru.ludwigandreas.testsupport.security.TestPrincipalBuilder;

/**
 * What an operator can see and do about reconciliation tasks and enumerations through the administrative surface.
 *
 * <p>Ported from the test of the reconciliation actuator endpoint, which these endpoints replace: every case it
 * covered is here, now over HTTP through the administrative guard, with the destructive-operations switch and the
 * {@code confirm} flag replaced by the privileged grant and the mandatory reason.
 */
@SpringBootTest(classes = ReconciliationTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "ludwig.security.enabled=true",
            "ludwig.security.jwt.enabled=false",
            "management.endpoints.web.exposure.include=reconciliation",
            "ludwig.admin.grants.reconciliation.read[0]=SYNC_OPERATOR",
            "ludwig.admin.grants.reconciliation.read[1]=SYNC_ADMIN",
            "ludwig.admin.grants.reconciliation.operate[0]=SYNC_OPERATOR",
            "ludwig.admin.grants.reconciliation.operate[1]=SYNC_ADMIN",
            "ludwig.admin.grants.reconciliation.privileged[0]=SYNC_ADMIN"})
@Import(PostgresContainerConfiguration.class)
@AutoConfigureMockMvc
class ReconciliationAdminIntegrationTest {

    private static final String TASK = ItemCatalogueEnumeration.NAME;
    private static final String SCOPE = ItemCatalogueEnumeration.SCOPE;
    private static final String BASE = "/admin/v1/reconciliation/tasks/";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private EnumerationFixture fixture;

    @Autowired
    private StubCollectionPartner partner;

    @Autowired
    private ItemCatalogueEnumeration catalogue;

    @Autowired
    private RecordingAuditSink audit;

    @BeforeEach
    void resetState() {
        fixture.reset();
        audit.reset();
    }

    private static RequestPostProcessor operator() {
        return TestPrincipalBuilder.user("olga").roles("SYNC_OPERATOR").postProcessor();
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

    private List<AuditEvent> adminEvents() {
        return audit.withAction("reconciliation.operate").isEmpty()
                ? audit.withAction("reconciliation.privileged") : audit.withAction("reconciliation.operate");
    }

    @Test
    void theOverviewListsEnumerationsAndTheirPassesBesideTheTasks() throws Exception {
        JsonNode overview = json(get("/admin/v1/reconciliation/tasks").with(operator()));

        assertThat(overview.path("enumerations").findValuesAsText("name")).contains(TASK);
        assertThat(overview.path("passes").toString()).contains(TASK + ":walk", "billing-status:hot");
        assertThat(overview.path("tasks").findValuesAsText("name")).contains("billing-status").doesNotContain(TASK);
    }

    @Test
    void anEnumerationIsDescribedThroughTheSameTaskPath() throws Exception {
        partner.lists(SCOPE, item("I-1", "one"));
        fixture.walk(TASK, SCOPE);

        JsonNode detail = json(get(BASE + TASK).with(operator()));

        assertThat(detail.path("kind").asText()).isEqualTo("enumeration");
        assertThat(detail.path("scopes").asLong()).isEqualTo(1L);
        assertThat(detail.path("freshnessLagSeconds").asDouble()).isEqualTo(0.0);
    }

    @Test
    void aScopeShowsItsWalkStateAndHowManyMembersItHas() throws Exception {
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", "two"));
        fixture.walk(TASK, SCOPE);

        JsonNode scope = json(get(BASE + TASK + "/scopes/" + SCOPE).with(operator()));

        assertThat(scope.path("scope").asText()).isEqualTo(SCOPE);
        assertThat(scope.path("generation").asLong()).isEqualTo(1L);
        assertThat(scope.path("members").asLong()).isEqualTo(2L);
        assertThat(scope.path("lastFullAt").isNull()).isFalse();
    }

    @Test
    void aPreviewSaysWhatAWalkWouldDoWritesNothingAndIsNotAudited() throws Exception {
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", "two"), item("I-3", "three"));
        fixture.walk(TASK, SCOPE);
        SyncScopeState before = fixture.state(TASK, SCOPE);
        catalogue.recorded().reset();
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", "two, revised"), item("I-4", "four"));

        JsonNode preview = json(post(BASE + TASK + "/scopes/" + SCOPE + "/preview").with(operator()));

        assertThat(preview.path("complete").asBoolean()).isTrue();
        assertThat(preview.path("new").asInt()).isEqualTo(1);
        assertThat(preview.path("changed").asInt()).isEqualTo(1);
        assertThat(preview.path("unchanged").asInt()).isEqualTo(1);
        assertThat(preview.path("absent").asLong()).isEqualTo(1L);
        assertThat(catalogue.recorded().appliedCalls()).isEmpty();
        assertThat(fixture.memberKeys(TASK, SCOPE)).containsExactly("I-1", "I-2", "I-3");
        assertThat(fixture.local("I-2").getTitle()).isEqualTo("two");
        assertThat(fixture.state(TASK, SCOPE).getVersion()).isEqualTo(before.getVersion());
        assertThat(adminEvents()).isEmpty();
    }

    @Test
    void walkingAScopeOnDemandIsRecordedAgainstTheOperator() throws Exception {
        partner.lists(SCOPE, item("I-1", "one"));

        JsonNode result = json(post(BASE + TASK + "/scopes/" + SCOPE + "/walk").with(operator())
                .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"full\"}"));

        assertThat(result.path("applied").asInt()).isEqualTo(1);
        assertThat(result.path("kind").asText()).isEqualTo("full");
        assertThat(audit.withAction("reconciliation.operate")).singleElement().satisfies(event -> {
            assertThat(event.category()).isEqualTo("admin");
            assertThat(event.actor().subject()).isEqualTo("olga");
            assertThat(event.attributes()).containsEntry("action", "walk");
        });
    }

    @Test
    void confirmingAWithheldSweepNeedsThePrivilegedGrant() throws Exception {
        givenAWithheldSweep();

        MvcResult result = perform(post(BASE + TASK + "/scopes/" + SCOPE + "/sweep/confirm")
                .header(AdminInvocation.REASON_HEADER, "partner confirmed the removals").with(operator()));

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(catalogue.recorded().leftCalls()).isEmpty();
        assertThat(fixture.memberKeys(TASK, SCOPE)).hasSize(4);
    }

    @Test
    void confirmingAWithheldSweepNeedsAReasonEvenWithTheGrant() throws Exception {
        givenAWithheldSweep();

        MvcResult result = perform(post(BASE + TASK + "/scopes/" + SCOPE + "/sweep/confirm").with(admin()));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(catalogue.recorded().leftCalls()).isEmpty();
    }

    @Test
    void confirmingAWithheldSweepWithTheGrantAndAReasonReportsTheMembers() throws Exception {
        givenAWithheldSweep();

        JsonNode result = json(post(BASE + TASK + "/scopes/" + SCOPE + "/sweep/confirm")
                .header(AdminInvocation.REASON_HEADER, "partner confirmed the removals").with(admin()));

        assertThat(result.path("reported").asInt()).isEqualTo(4);
        assertThat(catalogue.recorded().leftCalls()).hasSize(4);
        assertThat(audit.withAction("reconciliation.privileged")).singleElement().satisfies(event ->
                assertThat(event.attributes()).containsEntry("reason", "partner confirmed the removals"));
    }

    @Test
    void discardingAWithheldSweepNeedsNoSpecialPermission() throws Exception {
        givenAWithheldSweep();

        json(post(BASE + TASK + "/scopes/" + SCOPE + "/sweep/discard").with(operator()));

        assertThat(fixture.state(TASK, SCOPE).getSweepHeldAt()).isNull();
        assertThat(catalogue.recorded().leftCalls()).isEmpty();
    }

    @Test
    void resettingAScopeClearsItsCursorAndWatermarkAndKeepsItsMembers() throws Exception {
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", "two"), item("I-3", "three"));
        partner.failsAt(SCOPE, 2);
        fixture.walk(TASK, SCOPE);
        assertThat(fixture.state(TASK, SCOPE).getWalkCursor()).isEqualTo("2");

        json(post(BASE + TASK + "/scopes/" + SCOPE + "/reset").with(operator()));

        SyncScopeState state = fixture.state(TASK, SCOPE);
        assertThat(state.getWalkCursor()).isNull();
        assertThat(state.getWalkKind()).isNull();
        assertThat(state.getWatermark()).isNull();
        assertThat(state.getLastFullAt()).isNull();
        assertThat(fixture.memberKeys(TASK, SCOPE)).containsExactly("I-1", "I-2");
    }

    @Test
    void requeueingMembersIsATaskLevelAction() throws Exception {
        partner.lists(SCOPE, item("I-1", ItemReconciler.EXPLODE));
        fixture.walk(TASK, SCOPE);
        Thread.sleep(50);
        fixture.fullWalk(TASK, SCOPE);
        assertThat(fixture.member(TASK, SCOPE, "I-1").getStatus()).isEqualTo(ScopeMemberStatus.QUARANTINED);

        JsonNode result = json(post(BASE + TASK + "/members/requeue").with(operator()));

        assertThat(result.path("requeued").asLong()).isEqualTo(1L);
        assertThat(fixture.member(TASK, SCOPE, "I-1").getStatus()).isEqualTo(ScopeMemberStatus.ACTIVE);
        assertThat(fixture.member(TASK, SCOPE, "I-1").getAttempts()).isZero();
    }

    @Test
    void runningAnEnumerationOnDemandRunsItsWalkPassAndAnswers202WithTheTaskAsStatus() throws Exception {
        partner.lists(SCOPE, item("I-1", "one"));
        json(post(BASE + TASK + "/discover").with(operator()));

        MvcResult result = perform(post(BASE + TASK + "/runs").with(operator()));

        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        assertThat(result.getResponse().getHeader("Location")).isEqualTo(BASE + TASK);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("operation").path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(body.path("result").path("pass").asText()).isEqualTo(TASK + ":walk");
        assertThat(body.path("result").path("ran").asBoolean()).isTrue();
        assertThat(fixture.memberKeys(TASK, SCOPE)).containsExactly("I-1");
    }

    @Test
    void aScopeActionOnAKeyAddressedTaskIsA404() throws Exception {
        MvcResult result = perform(post(BASE + "billing-status/scopes/X/walk").with(operator()));

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString())
                .contains("ludwig.reconciliation.admin.enumeration-not-found");
    }

    @Test
    void anUnknownTaskIsA404AndABackfillWithoutKeysIsA400() throws Exception {
        assertThat(perform(get(BASE + "no-such-task").with(operator())).getResponse().getStatus()).isEqualTo(404);
        assertThat(perform(post(BASE + "billing-status/backfills").with(operator())
                .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[]}")).getResponse().getStatus())
                .isEqualTo(400);
    }

    @Test
    void theActuatorEndpointIsGoneEvenWhenItsIdIsExposed() throws Exception {
        assertThat(perform(get("/actuator/reconciliation").with(admin())).getResponse().getStatus()).isEqualTo(404);
    }

    private void givenAWithheldSweep() {
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", "two"), item("I-3", "three"), item("I-4", "four"));
        fixture.walk(TASK, SCOPE);
        partner.lists(SCOPE);
        fixture.fullWalk(TASK, SCOPE);
        catalogue.recorded().reset();
    }
}
