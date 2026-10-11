package ru.ludwigandreas.reconciliation.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import ru.ludwigandreas.reconciliation.engine.enumeration.EnumerationRunner;
import ru.ludwigandreas.reconciliation.engine.enumeration.SweepService;
import ru.ludwigandreas.reconciliation.engine.enumeration.WalkReport;
import ru.ludwigandreas.reconciliation.entity.SyncScopeState;
import ru.ludwigandreas.reconciliation.integration.testmodel.ItemCatalogueDriftEnumeration;
import ru.ludwigandreas.reconciliation.integration.testmodel.ItemCatalogueEnumeration;
import ru.ludwigandreas.reconciliation.integration.testmodel.ItemReconciler;
import ru.ludwigandreas.reconciliation.integration.testmodel.RecordingAuditSink;
import ru.ludwigandreas.reconciliation.integration.testmodel.StubCollectionPartner;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.ludwigandreas.reconciliation.integration.EnumerationFixture.item;

/**
 * The guards on telling an integration that something is gone: how many walks have to agree, and
 * what happens when a partner suddenly lists nothing at all.
 */
@SpringBootTest(classes = ReconciliationTestApplication.class)
@Import(PostgresContainerConfiguration.class)
class EnumerationSweepIntegrationTest {

    private static final String TASK = ItemCatalogueEnumeration.NAME;
    private static final String SCOPE = ItemCatalogueEnumeration.SCOPE;
    private static final String DRIFT_TASK = ItemCatalogueDriftEnumeration.NAME;
    private static final String DRIFT_SCOPE = ItemCatalogueDriftEnumeration.SCOPE;

    @Autowired
    private EnumerationFixture fixture;

    @Autowired
    private StubCollectionPartner partner;

    @Autowired
    private ItemCatalogueEnumeration catalogue;

    @Autowired
    private ItemCatalogueDriftEnumeration drift;

    @Autowired
    private EnumerationRunner runner;

    @Autowired
    private RecordingAuditSink audit;

    @BeforeEach
    void resetState() {
        fixture.reset();
    }

    @Test
    void aMemberMissedOnceIsKeptWhenTwoConsecutiveMissesAreRequired() {
        partner.lists(DRIFT_SCOPE, item("D-1", "one"), item("D-2", "two"), item("D-3", "three"));
        fixture.walk(DRIFT_TASK, DRIFT_SCOPE);
        partner.lists(DRIFT_SCOPE, item("D-1", "one"), item("D-2", "two"));

        fixture.fullWalk(DRIFT_TASK, DRIFT_SCOPE);

        assertThat(drift.recorded().leftCalls()).isEmpty();
        assertThat(fixture.memberKeys(DRIFT_TASK, DRIFT_SCOPE)).contains("D-3");
    }

    @Test
    void aMemberMissedTwiceRunningIsReported() {
        partner.lists(DRIFT_SCOPE, item("D-1", "one"), item("D-2", "two"), item("D-3", "three"));
        fixture.walk(DRIFT_TASK, DRIFT_SCOPE);
        partner.lists(DRIFT_SCOPE, item("D-1", "one"), item("D-2", "two"));
        fixture.fullWalk(DRIFT_TASK, DRIFT_SCOPE);

        fixture.fullWalk(DRIFT_TASK, DRIFT_SCOPE);

        assertThat(drift.recorded().leftCalls()).containsExactly(new ItemReconciler.Left(DRIFT_SCOPE, "D-3"));
        assertThat(fixture.local("D-3").isRemoved()).isTrue();
        assertThat(fixture.memberKeys(DRIFT_TASK, DRIFT_SCOPE)).containsExactly("D-1", "D-2");
    }

    @Test
    void aMemberThatReappearsBetweenTheTwoWalksIsNotReported() {
        partner.lists(DRIFT_SCOPE, item("D-1", "one"), item("D-2", "two"), item("D-3", "three"));
        fixture.walk(DRIFT_TASK, DRIFT_SCOPE);
        partner.lists(DRIFT_SCOPE, item("D-1", "one"), item("D-2", "two"));
        fixture.fullWalk(DRIFT_TASK, DRIFT_SCOPE);
        partner.lists(DRIFT_SCOPE, item("D-1", "one"), item("D-2", "two"), item("D-3", "three"));
        fixture.fullWalk(DRIFT_TASK, DRIFT_SCOPE);
        partner.lists(DRIFT_SCOPE, item("D-1", "one"), item("D-2", "two"));

        fixture.fullWalk(DRIFT_TASK, DRIFT_SCOPE);

        assertThat(drift.recorded().leftCalls()).isEmpty();
    }

    @Test
    void aPartnerThatSuddenlyListsNothingDoesNotEmptyTheScope() {
        givenFourMembersAndAnEmptyListing();

        WalkReport report = fixture.fullWalk(TASK, SCOPE);

        assertThat(report.sweep().verdict()).isEqualTo(SweepService.Verdict.HELD);
        assertThat(catalogue.recorded().leftCalls()).isEmpty();
        assertThat(fixture.memberKeys(TASK, SCOPE)).hasSize(4);
        SyncScopeState state = fixture.state(TASK, SCOPE);
        assertThat(state.getSweepHeldAt()).isNotNull();
        assertThat(state.getSweepHeldCount()).isEqualTo(4);
        assertThat(audit.withAction("scope.sweep.held")).hasSize(1);
    }

    @Test
    void anOperatorConfirmingAWithheldSweepReportsTheMembers() {
        givenFourMembersAndAnEmptyListing();
        fixture.fullWalk(TASK, SCOPE);

        SweepService.Result result = runner.confirmSweep(fixture.enumeration(TASK), SCOPE).orElseThrow();

        assertThat(result.reported()).isEqualTo(4);
        assertThat(catalogue.recorded().leftCalls()).hasSize(4);
        assertThat(fixture.memberKeys(TASK, SCOPE)).isEmpty();
        assertThat(fixture.state(TASK, SCOPE).getSweepHeldAt()).isNull();
    }

    @Test
    void anOperatorDiscardingAWithheldSweepReportsNothingAndMakesTheScopeDueInFull() {
        givenFourMembersAndAnEmptyListing();
        fixture.fullWalk(TASK, SCOPE);

        boolean discarded = runner.discardSweep(fixture.enumeration(TASK), SCOPE);

        assertThat(discarded).isTrue();
        assertThat(catalogue.recorded().leftCalls()).isEmpty();
        SyncScopeState state = fixture.state(TASK, SCOPE);
        assertThat(state.getSweepHeldAt()).isNull();
        assertThat(state.getLastFullAt()).isNull();
        assertThat(state.getNextDueAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void aWithheldSweepClearsItselfWhenThePartnerListsTheMembersAgain() {
        givenFourMembersAndAnEmptyListing();
        fixture.fullWalk(TASK, SCOPE);
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", "two"), item("I-3", "three"), item("I-4", "four"));

        WalkReport report = fixture.fullWalk(TASK, SCOPE);

        assertThat(report.sweep().verdict()).isEqualTo(SweepService.Verdict.NOTHING_TO_SWEEP);
        assertThat(fixture.state(TASK, SCOPE).getSweepHeldAt()).isNull();
    }

    @Test
    void aMemberLeavingIsAuditedWithItsTaskScopeAndKey() {
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", "two"), item("I-3", "three"));
        fixture.walk(TASK, SCOPE);
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", "two"));

        fixture.fullWalk(TASK, SCOPE);

        assertThat(audit.withAction("scope.member.left")).singleElement().satisfies(event -> {
            assertThat(event.category()).isEqualTo("reconciliation");
            assertThat(event.attributes()).containsEntry("taskName", TASK)
                    .containsEntry("subject", SCOPE + "/I-3")
                    .containsEntry("eventCategory", "SCOPE");
        });
    }

    private void givenFourMembersAndAnEmptyListing() {
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", "two"), item("I-3", "three"), item("I-4", "four"));
        fixture.walk(TASK, SCOPE);
        partner.lists(SCOPE);
    }
}
