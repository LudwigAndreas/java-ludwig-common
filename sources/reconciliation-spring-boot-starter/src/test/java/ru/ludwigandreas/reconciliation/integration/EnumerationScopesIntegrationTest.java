package ru.ludwigandreas.reconciliation.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import ru.ludwigandreas.reconciliation.engine.enumeration.WalkReport;
import ru.ludwigandreas.reconciliation.entity.ScopeWalkKind;
import ru.ludwigandreas.reconciliation.integration.testmodel.ItemReconciler;
import ru.ludwigandreas.reconciliation.integration.testmodel.ScopedItemsEnumeration;
import ru.ludwigandreas.reconciliation.integration.testmodel.StubCollectionPartner;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.ludwigandreas.reconciliation.integration.EnumerationFixture.item;

/**
 * A scope per local row: each scope keeps its own cursor and watermark, a member belongs to a scope
 * rather than to the task, and only a full walk can show that one is gone.
 */
@SpringBootTest(classes = ReconciliationTestApplication.class)
@Import(PostgresContainerConfiguration.class)
class EnumerationScopesIntegrationTest {

    private static final String TASK = ScopedItemsEnumeration.NAME;
    private static final Instant T1 = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-01-01T11:00:00Z");

    @Autowired
    private EnumerationFixture fixture;

    @Autowired
    private StubCollectionPartner partner;

    @Autowired
    private ScopedItemsEnumeration scoped;

    @BeforeEach
    void resetState() {
        fixture.reset();
    }

    @Test
    void aScopeAddedLaterGetsAFullFirstWalkWhileTheOlderOneWalksIncrementally() {
        fixture.givenScope("X");
        partner.lists("X", item("I-1", "one", T1));
        fixture.walk(TASK, "X");
        assertThat(fixture.state(TASK, "X").getWatermark()).isEqualTo(T1);
        fixture.givenScope("Y");
        partner.lists("Y", item("I-9", "nine", T1));
        partner.forgetCalls();

        WalkReport older = fixture.walk(TASK, "X");
        WalkReport newer = fixture.walk(TASK, "Y");

        assertThat(older.kind()).isEqualTo(ScopeWalkKind.INCREMENTAL);
        assertThat(newer.kind()).isEqualTo(ScopeWalkKind.FULL);
        assertThat(partner.calls()).containsExactly(
                new StubCollectionPartner.Call("X", null, T1),
                new StubCollectionPartner.Call("Y", null, null));
        assertThat(fixture.memberKeys(TASK, "Y")).containsExactly("I-9");
    }

    @Test
    void anIncrementalWalkAppliesWhatChangedSinceTheWatermark() {
        fixture.givenScope("X");
        partner.lists("X", item("I-1", "one", T1), item("I-2", "two", T1));
        fixture.walk(TASK, "X");
        partner.lists("X", item("I-1", "one", T1), item("I-2", "two, revised", T2));

        WalkReport report = fixture.walk(TASK, "X");

        assertThat(report.kind()).isEqualTo(ScopeWalkKind.INCREMENTAL);
        assertThat(report.members().applied()).isEqualTo(1);
        assertThat(fixture.local("I-2").getTitle()).isEqualTo("two, revised");
        assertThat(fixture.state(TASK, "X").getWatermark()).isEqualTo(T2);
    }

    @Test
    void anIncrementalWalkNeverReportsAMemberAsGone() {
        fixture.givenScope("X");
        partner.lists("X", item("I-1", "one", T1), item("I-2", "two", T1));
        fixture.walk(TASK, "X");
        partner.lists("X", item("I-1", "one, revised", T2));

        WalkReport report = fixture.walk(TASK, "X");

        assertThat(report.kind()).isEqualTo(ScopeWalkKind.INCREMENTAL);
        assertThat(report.sweep()).isNull();
        assertThat(scoped.recorded().leftCalls()).isEmpty();
        assertThat(fixture.memberKeys(TASK, "X")).containsExactly("I-1", "I-2");
    }

    @Test
    void aMemberOfTwoScopesThatLeavesOneStaysAMemberOfTheOther() {
        fixture.givenScope("X");
        fixture.givenScope("Y");
        partner.lists("X", item("I-1", "one", T1), item("I-2", "two", T1));
        partner.lists("Y", item("I-1", "one", T1), item("I-3", "three", T1));
        fixture.walk(TASK, "X");
        fixture.walk(TASK, "Y");
        partner.lists("X", item("I-2", "two", T1));

        fixture.fullWalk(TASK, "X");

        assertThat(scoped.recorded().leftCalls()).containsExactly(new ItemReconciler.Left("X", "I-1"));
        assertThat(fixture.memberKeys(TASK, "X")).containsExactly("I-2");
        assertThat(fixture.memberKeys(TASK, "Y")).containsExactly("I-1", "I-3");
    }

    @Test
    void aMemberThatLeftAndCameBackArrivesAsNewAndIsRestored() {
        fixture.givenScope("X");
        partner.lists("X", item("I-1", "one", T1), item("I-2", "two", T1));
        fixture.walk(TASK, "X");
        partner.lists("X", item("I-2", "two", T1));
        fixture.fullWalk(TASK, "X");
        assertThat(fixture.local("I-1").isRemoved()).isTrue();
        scoped.recorded().reset();
        partner.lists("X", item("I-1", "one", T1), item("I-2", "two", T1));

        fixture.fullWalk(TASK, "X");

        assertThat(scoped.recorded().appliedCalls()).containsExactly(new ItemReconciler.Applied("X", "I-1", true));
        assertThat(fixture.local("I-1").isRemoved()).isFalse();
    }

    @Test
    void aScopeWhoseLocalRowIsGoneIsForgottenWithoutReportingItsMembers() {
        fixture.givenScope("X");
        partner.lists("X", item("I-1", "one", T1), item("I-2", "two", T1));
        fixture.walk(TASK, "X");
        fixture.removeScope("X");
        partner.forgetCalls();

        WalkReport report = fixture.walk(TASK, "X");

        assertThat(report.status()).isEqualTo(WalkReport.Status.REMOVED);
        assertThat(fixture.memberKeys(TASK, "X")).isEmpty();
        assertThat(scoped.recorded().leftCalls()).isEmpty();
        assertThat(partner.calls()).isEmpty();
    }
}
