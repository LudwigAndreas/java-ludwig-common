package ru.ludwigandreas.reconciliation.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import ru.ludwigandreas.reconciliation.engine.enumeration.EnumerationRunner;
import ru.ludwigandreas.reconciliation.engine.enumeration.SweepService;
import ru.ludwigandreas.reconciliation.engine.enumeration.WalkReport;
import ru.ludwigandreas.reconciliation.entity.ScopeMemberStatus;
import ru.ludwigandreas.reconciliation.entity.SyncScopeMember;
import ru.ludwigandreas.reconciliation.integration.testmodel.ItemCatalogueEnumeration;
import ru.ludwigandreas.reconciliation.integration.testmodel.ItemReconciler;
import ru.ludwigandreas.reconciliation.integration.testmodel.StubCollectionPartner;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;


import static org.assertj.core.api.Assertions.assertThat;
import static ru.ludwigandreas.reconciliation.integration.EnumerationFixture.item;

/**
 * One member the integration cannot apply costs its scope that member, not the walk - and a walk
 * that could not apply something it listed is not trusted to say what is gone.
 */
@SpringBootTest(classes = ReconciliationTestApplication.class)
@Import(PostgresContainerConfiguration.class)
class EnumerationFailureIsolationIntegrationTest {

    private static final String TASK = ItemCatalogueEnumeration.NAME;
    private static final String SCOPE = ItemCatalogueEnumeration.SCOPE;

    @Autowired
    private EnumerationFixture fixture;

    @Autowired
    private StubCollectionPartner partner;

    @Autowired
    private ItemCatalogueEnumeration catalogue;

    @Autowired
    private EnumerationRunner runner;

    @BeforeEach
    void resetState() {
        fixture.reset();
    }

    @Test
    void oneMemberThatCannotBeAppliedDoesNotStopTheOthersOrTheWalk() {
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", ItemReconciler.EXPLODE), item("I-3", "three"));

        WalkReport report = fixture.walk(TASK, SCOPE);

        assertThat(report.status()).isEqualTo(WalkReport.Status.COMPLETED);
        assertThat(report.members().failed()).isEqualTo(1);
        // I-1 shares a page with the failure, so the page was replayed item by item: it is applied
        // exactly once, because the first attempt rolled back with the page.
        assertThat(fixture.local("I-1").getSyncCount()).isEqualTo(1);
        assertThat(fixture.local("I-3").getTitle()).isEqualTo("three");
        SyncScopeMember failed = fixture.member(TASK, SCOPE, "I-2");
        assertThat(failed.getStatus()).isEqualTo(ScopeMemberStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getLastError()).contains("cannot apply I-2");
    }

    @Test
    void aWalkThatListedAMemberItCouldNotApplyDoesNotSweep() {
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", "two"), item("I-3", "three"), item("I-4", "four"));
        fixture.walk(TASK, SCOPE);
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", ItemReconciler.EXPLODE), item("I-3", "three"));

        WalkReport report = fixture.fullWalk(TASK, SCOPE);

        assertThat(report.sweep().verdict()).isEqualTo(SweepService.Verdict.NOT_CLEAN);
        assertThat(catalogue.recorded().leftCalls()).isEmpty();
        assertThat(fixture.memberKeys(TASK, SCOPE)).contains("I-4");
    }

    @Test
    void aMemberThatKeepsFailingIsQuarantinedAndNotHandedOverAgain() throws InterruptedException {
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", ItemReconciler.EXPLODE));
        fixture.walk(TASK, SCOPE);
        // The retry budget is two attempts with a millisecond of backoff between them.
        Thread.sleep(50);
        fixture.fullWalk(TASK, SCOPE);
        assertThat(fixture.member(TASK, SCOPE, "I-2").getStatus()).isEqualTo(ScopeMemberStatus.QUARANTINED);
        catalogue.recorded().reset();

        WalkReport report = fixture.fullWalk(TASK, SCOPE);

        assertThat(catalogue.recorded().appliedCalls()).isEmpty();
        assertThat(report.members().held()).isEqualTo(1);
        assertThat(report.sweep().verdict()).isEqualTo(SweepService.Verdict.NOT_CLEAN);
    }

    @Test
    void aRequeuedMemberGetsAFreshBudgetAndItsScopeSweepsOnceItApplies() throws InterruptedException {
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", "two"), item("I-3", "three"), item("I-4", "four"));
        fixture.walk(TASK, SCOPE);
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", ItemReconciler.EXPLODE), item("I-3", "three"));
        fixture.fullWalk(TASK, SCOPE);
        Thread.sleep(50);
        fixture.fullWalk(TASK, SCOPE);
        assertThat(fixture.member(TASK, SCOPE, "I-2").getStatus()).isEqualTo(ScopeMemberStatus.QUARANTINED);

        long requeued = runner.requeueMembers(fixture.enumeration(TASK));
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", "two, fixed"), item("I-3", "three"));
        WalkReport report = fixture.walk(TASK, SCOPE);

        assertThat(requeued).isEqualTo(1);
        assertThat(fixture.member(TASK, SCOPE, "I-2").getStatus()).isEqualTo(ScopeMemberStatus.ACTIVE);
        assertThat(fixture.local("I-2").getTitle()).isEqualTo("two, fixed");
        assertThat(report.sweep().verdict()).isEqualTo(SweepService.Verdict.SWEPT);
        assertThat(catalogue.recorded().leftCalls()).containsExactly(new ItemReconciler.Left(SCOPE, "I-4"));
    }
}
