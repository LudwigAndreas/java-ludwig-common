package ru.ludwigandreas.reconciliation.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import ru.ludwigandreas.reconciliation.engine.enumeration.EnumerationRegistry;
import ru.ludwigandreas.reconciliation.engine.enumeration.RegisteredEnumeration;
import ru.ludwigandreas.reconciliation.engine.enumeration.ScopeWalker;
import ru.ludwigandreas.reconciliation.engine.enumeration.WalkReport;
import ru.ludwigandreas.reconciliation.entity.SyncScopeState;
import ru.ludwigandreas.reconciliation.integration.testmodel.ScopedItemsEnumeration;
import ru.ludwigandreas.reconciliation.integration.testmodel.StubCollectionPartner;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.ludwigandreas.reconciliation.integration.EnumerationFixture.item;

/**
 * The watermark moves when a walk completes and at no other time, so an interrupted walk can never
 * leave it past pages that were not read.
 */
@SpringBootTest(classes = ReconciliationTestApplication.class)
@Import(PostgresContainerConfiguration.class)
class EnumerationWatermarkIntegrationTest {

    private static final String TASK = ScopedItemsEnumeration.NAME;
    private static final Instant T1 = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-01-01T11:00:00Z");

    @Autowired
    private EnumerationFixture fixture;

    @Autowired
    private StubCollectionPartner partner;

    @Autowired
    private EnumerationRegistry registry;

    @Autowired
    private ScopeWalker walker;

    @BeforeEach
    void resetState() {
        fixture.reset();
        fixture.givenScope("X");
        partner.lists("X", item("I-1", "one", T1), item("I-2", "two", T1), item("I-3", "three", T2));
    }

    @Test
    void aWalkThatFailsPartWayDoesNotMoveTheWatermark() {
        partner.failsAt("X", 2);

        fixture.walk(TASK, "X");

        SyncScopeState state = fixture.state(TASK, "X");
        assertThat(state.getWatermark()).isNull();
        assertThat(state.getHighWater()).isEqualTo(T1);
    }

    @Test
    void completingTheWalkMovesTheWatermarkToTheNewestChangeSeen() {
        partner.failsAt("X", 2);
        fixture.walk(TASK, "X");
        partner.failsAt("X", -1);

        fixture.walk(TASK, "X");

        SyncScopeState state = fixture.state(TASK, "X");
        assertThat(state.getWatermark()).isEqualTo(T2);
        assertThat(state.getHighWater()).isNull();
    }

    @Test
    void aWalkStoppedAtTheRunTimeoutKeepsItsPlaceAndItsWatermark() {
        fixture.walk(TASK, "X");
        Instant watermark = fixture.state(TASK, "X").getWatermark();
        partner.lists("X", item("I-1", "one", T1), item("I-2", "two", T1), item("I-3", "three", T2),
                item("I-4", "four", T2.plusSeconds(60)));
        partner.forgetCalls();

        WalkReport report = walkPastTheDeadline(registry.find(TASK).orElseThrow());

        assertThat(report.status()).isEqualTo(WalkReport.Status.INTERRUPTED);
        assertThat(partner.calls()).isEmpty();
        SyncScopeState state = fixture.state(TASK, "X");
        assertThat(state.getWatermark()).isEqualTo(watermark);
        assertThat(state.getWalkKind()).isNotNull();
        assertThat(state.getNextDueAt()).isBeforeOrEqualTo(Instant.now());
    }

    private <S, K, M, N> WalkReport walkPastTheDeadline(RegisteredEnumeration<S, K, M, N> enumeration) {
        return walker.walk(enumeration, fixture.state(TASK, "X"), UUID.randomUUID(), Instant.now().minusSeconds(1));
    }
}
