package ru.ludwigandreas.reconciliation.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.reconciliation.actuator.ReconciliationEndpoint;
import ru.ludwigandreas.reconciliation.config.ReconciliationProperties;
import ru.ludwigandreas.reconciliation.engine.ReconciliationRuntime;
import ru.ludwigandreas.reconciliation.engine.TaskRegistry;
import ru.ludwigandreas.reconciliation.engine.TaskRunner;
import ru.ludwigandreas.reconciliation.engine.TaskStateService;
import ru.ludwigandreas.reconciliation.engine.enumeration.EnumerationOperations;
import ru.ludwigandreas.reconciliation.entity.ScopeMemberStatus;
import ru.ludwigandreas.reconciliation.entity.SyncScopeState;
import ru.ludwigandreas.reconciliation.integration.testmodel.ItemCatalogueEnumeration;
import ru.ludwigandreas.reconciliation.integration.testmodel.ItemReconciler;
import ru.ludwigandreas.reconciliation.integration.testmodel.RecordingAuditSink;
import ru.ludwigandreas.reconciliation.integration.testmodel.StubCollectionPartner;
import ru.ludwigandreas.reconciliation.quota.DatabaseQuota;
import ru.ludwigandreas.reconciliation.repository.QuotaLeaseRepository;
import ru.ludwigandreas.reconciliation.repository.SyncInboxRecordRepository;
import ru.ludwigandreas.reconciliation.repository.SyncRemoteJobRepository;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static ru.ludwigandreas.reconciliation.integration.EnumerationFixture.item;

/**
 * What an operator can see and do about an enumeration through the existing {@code reconciliation}
 * endpoint.
 *
 * <p>The endpoint is built by hand here, from the context's beans, for the same reason the other
 * integration tests drive passes by hand: the test context does not expose actuator endpoints, and
 * what is under test is what the operations do, not how they are routed.
 */
@SpringBootTest(classes = ReconciliationTestApplication.class)
@Import(PostgresContainerConfiguration.class)
class EnumerationEndpointIntegrationTest {

    private static final String TASK = ItemCatalogueEnumeration.NAME;
    private static final String SCOPE = ItemCatalogueEnumeration.SCOPE;

    @Autowired
    private EnumerationFixture fixture;

    @Autowired
    private StubCollectionPartner partner;

    @Autowired
    private ItemCatalogueEnumeration catalogue;

    @Autowired
    private RecordingAuditSink audit;

    @Autowired
    private ReconciliationProperties properties;

    @Autowired
    private EnumerationOperations operations;

    @Autowired
    private TaskRegistry registry;

    @Autowired
    private ReconciliationRuntime runtime;

    @Autowired
    private TaskRunner runner;

    @Autowired
    private TaskStateService taskState;

    @Autowired
    private SyncInboxRecordRepository records;

    @Autowired
    private SyncRemoteJobRepository jobs;

    @Autowired
    private QuotaLeaseRepository leases;

    @Autowired
    private DatabaseQuota quota;

    @Autowired
    private AuditSink auditSink;

    private ReconciliationEndpoint endpoint;

    @BeforeEach
    void resetState() {
        fixture.reset();
        properties.getEndpoint().setAllowDestructiveOperations(false);
        endpoint = new ReconciliationEndpoint(registry, runtime, runner, taskState, records, jobs, leases,
                quota, properties, auditSink, () -> Optional.of("operator"));
        endpoint.setEnumerations(operations);
    }

    @AfterEach
    void restoreTheSharedContext() {
        properties.getEndpoint().setAllowDestructiveOperations(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theOverviewListsEnumerationsAndTheirPassesBesideTheTasks() {
        Map<String, Object> overview = endpoint.overview();

        assertThat((List<Map<String, Object>>) overview.get("enumerations"))
                .extracting(view -> view.get("name")).contains(TASK);
        assertThat((List<String>) overview.get("passes")).contains(TASK + ":walk", "billing-status:hot");
        assertThat((List<Map<String, Object>>) overview.get("tasks"))
                .extracting(view -> view.get("name")).contains("billing-status").doesNotContain(TASK);
    }

    @Test
    void anEnumerationIsDescribedThroughTheSameTaskSelector() {
        partner.lists(SCOPE, item("I-1", "one"));
        fixture.walk(TASK, SCOPE);

        Map<String, Object> detail = endpoint.task(TASK);

        assertThat(detail).containsEntry("kind", "enumeration").containsEntry("scopes", 1L);
        assertThat(detail.get("freshnessLagSeconds")).isEqualTo(0.0);
    }

    @Test
    void aScopeShowsItsWalkStateAndHowManyMembersItHas() {
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", "two"));
        fixture.walk(TASK, SCOPE);

        Map<String, Object> scope = endpoint.scope(TASK, SCOPE);

        assertThat(scope).containsEntry("scope", SCOPE).containsEntry("generation", 1L).containsEntry("members", 2L);
        assertThat(scope.get("lastFullAt")).isNotNull();
    }

    @Test
    void aPreviewSaysWhatAWalkWouldDoAndWritesNothing() {
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", "two"), item("I-3", "three"));
        fixture.walk(TASK, SCOPE);
        SyncScopeState before = fixture.state(TASK, SCOPE);
        catalogue.recorded().reset();
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", "two, revised"), item("I-4", "four"));

        Map<String, Object> preview = endpoint.operateScope(TASK, SCOPE, "preview", null, null);

        assertThat(preview).containsEntry("complete", true).containsEntry("new", 1)
                .containsEntry("changed", 1).containsEntry("unchanged", 1).containsEntry("absent", 1L);
        assertThat(catalogue.recorded().appliedCalls()).isEmpty();
        assertThat(fixture.memberKeys(TASK, SCOPE)).containsExactly("I-1", "I-2", "I-3");
        assertThat(fixture.local("I-2").getTitle()).isEqualTo("two");
        assertThat(fixture.state(TASK, SCOPE).getVersion()).isEqualTo(before.getVersion());
    }

    @Test
    void walkingAScopeOnDemandIsRecordedAgainstTheOperator() {
        partner.lists(SCOPE, item("I-1", "one"));

        Map<String, Object> result = endpoint.operateScope(TASK, SCOPE, "walk", "full", null);

        assertThat(result).containsEntry("applied", 1).containsEntry("kind", "full");
        assertThat(audit.withAction("operator.walk")).singleElement().satisfies(event -> {
            assertThat(event.actor().toString()).contains("operator");
            assertThat(event.attributes()).containsEntry("eventCategory", "OPERATOR");
        });
    }

    @Test
    void confirmingAWithheldSweepIsRefusedWhereDestructiveOperationsAreOff() {
        givenAWithheldSweep();

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> endpoint.operateScope(TASK, SCOPE, "confirm-sweep", null, true))
                .withMessageContaining("allow-destructive-operations");
        assertThat(catalogue.recorded().leftCalls()).isEmpty();
        assertThat(fixture.memberKeys(TASK, SCOPE)).hasSize(4);
    }

    @Test
    void confirmingAWithheldSweepNeedsAnExplicitConfirmationEvenWhereItIsAllowed() {
        givenAWithheldSweep();
        properties.getEndpoint().setAllowDestructiveOperations(true);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> endpoint.operateScope(TASK, SCOPE, "confirm-sweep", null, null))
                .withMessageContaining("confirm=true");
        assertThat(catalogue.recorded().leftCalls()).isEmpty();
    }

    @Test
    void confirmingAWithheldSweepWithBothKeysReportsTheMembers() {
        givenAWithheldSweep();
        properties.getEndpoint().setAllowDestructiveOperations(true);

        Map<String, Object> result = endpoint.operateScope(TASK, SCOPE, "confirm-sweep", null, true);

        assertThat(result).containsEntry("reported", 4);
        assertThat(catalogue.recorded().leftCalls()).hasSize(4);
        assertThat(audit.withAction("operator.confirm-sweep")).hasSize(1);
    }

    @Test
    void discardingAWithheldSweepNeedsNoSpecialPermission() {
        givenAWithheldSweep();

        endpoint.operateScope(TASK, SCOPE, "discard-sweep", null, null);

        assertThat(fixture.state(TASK, SCOPE).getSweepHeldAt()).isNull();
        assertThat(catalogue.recorded().leftCalls()).isEmpty();
    }

    @Test
    void resettingAScopeClearsItsCursorAndWatermarkAndKeepsItsMembers() {
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", "two"), item("I-3", "three"));
        partner.failsAt(SCOPE, 2);
        fixture.walk(TASK, SCOPE);
        assertThat(fixture.state(TASK, SCOPE).getWalkCursor()).isEqualTo("2");

        endpoint.operateScope(TASK, SCOPE, "reset-scope", null, null);

        SyncScopeState state = fixture.state(TASK, SCOPE);
        assertThat(state.getWalkCursor()).isNull();
        assertThat(state.getWalkKind()).isNull();
        assertThat(state.getWatermark()).isNull();
        assertThat(state.getLastFullAt()).isNull();
        assertThat(fixture.memberKeys(TASK, SCOPE)).containsExactly("I-1", "I-2");
    }

    @Test
    void requeueingMembersIsATaskLevelActionOnTheExistingOperation() throws InterruptedException {
        partner.lists(SCOPE, item("I-1", ItemReconciler.EXPLODE));
        fixture.walk(TASK, SCOPE);
        Thread.sleep(50);
        fixture.fullWalk(TASK, SCOPE);
        assertThat(fixture.member(TASK, SCOPE, "I-1").getStatus()).isEqualTo(ScopeMemberStatus.QUARANTINED);

        Map<String, Object> result = endpoint.operate(TASK, "requeue-members", null, null, null, null, null);

        assertThat(result).containsEntry("requeued", 1L);
        assertThat(fixture.member(TASK, SCOPE, "I-1").getStatus()).isEqualTo(ScopeMemberStatus.ACTIVE);
        assertThat(fixture.member(TASK, SCOPE, "I-1").getAttempts()).isZero();
    }

    @Test
    void runningAnEnumerationOnDemandRunsItsWalkPass() {
        partner.lists(SCOPE, item("I-1", "one"));
        endpoint.operate(TASK, "discover", null, null, null, null, null);

        Map<String, Object> result = endpoint.operate(TASK, "run", null, null, null, null, null);

        assertThat(result).containsEntry("pass", TASK + ":walk").containsEntry("ran", true);
        assertThat(fixture.memberKeys(TASK, SCOPE)).containsExactly("I-1");
    }

    @Test
    void aScopeActionOnAKeyAddressedTaskIsRefusedByName() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> endpoint.operateScope("billing-status", "X", "walk", null, null))
                .withMessageContaining("No such enumeration: 'billing-status'");
    }

    private void givenAWithheldSweep() {
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", "two"), item("I-3", "three"), item("I-4", "four"));
        fixture.walk(TASK, SCOPE);
        partner.lists(SCOPE);
        fixture.fullWalk(TASK, SCOPE);
        catalogue.recorded().reset();
    }
}
