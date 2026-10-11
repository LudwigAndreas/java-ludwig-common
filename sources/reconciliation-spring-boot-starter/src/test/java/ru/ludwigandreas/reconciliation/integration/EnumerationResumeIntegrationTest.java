package ru.ludwigandreas.reconciliation.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import ru.ludwigandreas.reconciliation.engine.enumeration.WalkReport;
import ru.ludwigandreas.reconciliation.entity.SyncScopeState;
import ru.ludwigandreas.reconciliation.integration.testmodel.ItemCatalogueEnumeration;
import ru.ludwigandreas.reconciliation.integration.testmodel.StubCollectionPartner;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.ludwigandreas.reconciliation.integration.EnumerationFixture.item;

/**
 * A walk that stops part-way resumes from the page it stopped at, and the cursor never runs ahead
 * of the pages that were actually recorded.
 */
@SpringBootTest(classes = ReconciliationTestApplication.class)
@Import(PostgresContainerConfiguration.class)
class EnumerationResumeIntegrationTest {

    private static final String TASK = ItemCatalogueEnumeration.NAME;
    private static final String SCOPE = ItemCatalogueEnumeration.SCOPE;

    @Autowired
    private EnumerationFixture fixture;

    @Autowired
    private StubCollectionPartner partner;

    @BeforeEach
    void resetState() {
        fixture.reset();
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", "two"), item("I-3", "three"),
                item("I-4", "four"), item("I-5", "five"));
    }

    @Test
    void aFailedPageLeavesTheCursorOnThatPage() {
        partner.failsAt(SCOPE, 4);

        WalkReport report = fixture.walk(TASK, SCOPE);

        assertThat(report.status()).isEqualTo(WalkReport.Status.FAILED);
        SyncScopeState state = fixture.state(TASK, SCOPE);
        assertThat(state.getWalkCursor()).isEqualTo("4");
        assertThat(state.getWalkKind()).isNotNull();
        assertThat(state.getGeneration()).isZero();
        assertThat(fixture.memberKeys(TASK, SCOPE)).containsExactly("I-1", "I-2", "I-3", "I-4");
    }

    @Test
    void aFailedWalkBacksOffInsteadOfBeingRetriedOnEveryRun() {
        partner.failsAt(SCOPE, 2);

        fixture.walk(TASK, SCOPE);

        SyncScopeState state = fixture.state(TASK, SCOPE);
        assertThat(state.getWalkAttempts()).isEqualTo(1);
        assertThat(state.getLastError()).contains("stub partner is down");
        assertThat(state.getNextDueAt()).isAfter(Instant.now().minusSeconds(1));
    }

    @Test
    void theNextWalkResumesFromTheCheckpointAndDoesNotRefetchWhatItHas() {
        partner.failsAt(SCOPE, 4);
        fixture.walk(TASK, SCOPE);
        partner.failsAt(SCOPE, -1);
        partner.forgetCalls();

        WalkReport report = fixture.walk(TASK, SCOPE);

        assertThat(report.status()).isEqualTo(WalkReport.Status.COMPLETED);
        assertThat(partner.calls()).extracting(StubCollectionPartner.Call::cursor).containsExactly("4");
        assertThat(fixture.memberKeys(TASK, SCOPE)).containsExactly("I-1", "I-2", "I-3", "I-4", "I-5");
        SyncScopeState state = fixture.state(TASK, SCOPE);
        assertThat(state.getWalkCursor()).isNull();
        assertThat(state.getWalkKind()).isNull();
        assertThat(state.getGeneration()).isEqualTo(1);
    }
}
