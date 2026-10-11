package ru.ludwigandreas.reconciliation.unit;

import org.junit.jupiter.api.Test;
import ru.ludwigandreas.reconciliation.config.EnumerationSettings;
import ru.ludwigandreas.reconciliation.config.EnumerationSettingsResolver;
import ru.ludwigandreas.reconciliation.config.ReconciliationProperties;
import ru.ludwigandreas.reconciliation.engine.enumeration.WalkPlanner;
import ru.ludwigandreas.reconciliation.entity.ScopeWalkKind;
import ru.ludwigandreas.reconciliation.entity.SyncScopeState;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Which kind of walk a scope gets, and when it is next due. */
class WalkPlannerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final Duration FULL = Duration.ofHours(6);
    private static final Duration INCREMENTAL = Duration.ofMinutes(5);

    @Test
    void aScopeThatHasNeverCompletedAFullWalkGetsOne() {
        assertThat(WalkPlanner.kindFor(new SyncScopeState(), incremental(), NOW)).isEqualTo(ScopeWalkKind.FULL);
    }

    @Test
    void aWalkInProgressResumesAsTheKindItStartedAs() {
        SyncScopeState state = walked(NOW.minus(Duration.ofHours(7)), NOW.minus(Duration.ofHours(7)));
        state.setWalkKind(ScopeWalkKind.INCREMENTAL);

        // The full interval has elapsed, and it still resumes as incremental: a walk does not change
        // what it is half way through.
        assertThat(WalkPlanner.kindFor(state, incremental(), NOW)).isEqualTo(ScopeWalkKind.INCREMENTAL);
    }

    @Test
    void betweenFullWalksAnIncrementalEnumerationWalksIncrementally() {
        SyncScopeState state = walked(NOW.minus(Duration.ofHours(1)), NOW.minus(Duration.ofHours(1)));

        assertThat(WalkPlanner.kindFor(state, incremental(), NOW)).isEqualTo(ScopeWalkKind.INCREMENTAL);
    }

    @Test
    void onceTheFullIntervalHasElapsedTheWalkIsFullAgain() {
        SyncScopeState state = walked(NOW.minus(FULL), NOW.minus(Duration.ofMinutes(1)));

        assertThat(WalkPlanner.kindFor(state, incremental(), NOW)).isEqualTo(ScopeWalkKind.FULL);
    }

    @Test
    void anEnumerationWithNoIncrementalIntervalAlwaysWalksInFull() {
        SyncScopeState state = walked(NOW.minus(Duration.ofHours(1)), NOW.minus(Duration.ofHours(1)));

        assertThat(WalkPlanner.kindFor(state, fullOnly(), NOW)).isEqualTo(ScopeWalkKind.FULL);
    }

    @Test
    void aScopeWithNoWatermarkHasNothingToBoundADeltaWithSoItWalksInFull() {
        SyncScopeState state = walked(NOW.minus(Duration.ofHours(1)), NOW.minus(Duration.ofHours(1)));
        state.setWatermark(null);

        assertThat(WalkPlanner.kindFor(state, incremental(), NOW)).isEqualTo(ScopeWalkKind.FULL);
        // And it is due at the full cadence, not the incremental one - otherwise a feed that
        // publishes no timestamps would be listed in full every few minutes.
        assertThat(WalkPlanner.nextDue(state, incremental())).isEqualTo(state.getLastFullAt().plus(FULL));
    }

    @Test
    void afterAWalkAnIncrementalEnumerationIsNextDueAtTheIncrementalInterval() {
        SyncScopeState state = walked(NOW.minus(Duration.ofHours(1)), NOW);

        assertThat(WalkPlanner.nextDue(state, incremental())).isEqualTo(NOW.plus(INCREMENTAL));
    }

    @Test
    void theNextFullWalkIsDueFirstWhenItIsSoonerThanTheNextIncrementalOne() {
        Instant lastFull = NOW.minus(FULL).plus(Duration.ofMinutes(1));
        SyncScopeState state = walked(lastFull, NOW);

        assertThat(WalkPlanner.nextDue(state, incremental())).isEqualTo(lastFull.plus(FULL));
    }

    @Test
    void aFullOnlyEnumerationIsNextDueAFullIntervalAfterItsLastWalk() {
        SyncScopeState state = walked(NOW, NOW);

        assertThat(WalkPlanner.nextDue(state, fullOnly())).isEqualTo(NOW.plus(FULL));
    }

    private static SyncScopeState walked(Instant lastFull, Instant lastListed) {
        SyncScopeState state = new SyncScopeState();
        state.setLastFullAt(lastFull);
        state.setLastIncrementalAt(lastListed);
        state.setWatermark(lastFull);
        return state;
    }

    private static EnumerationSettings incremental() {
        return settings(INCREMENTAL);
    }

    private static EnumerationSettings fullOnly() {
        return settings(null);
    }

    private static EnumerationSettings settings(Duration incrementalInterval) {
        ReconciliationProperties.Enumeration enumeration = new ReconciliationProperties.Enumeration();
        enumeration.setRefresh(new ReconciliationProperties.Refresh());
        enumeration.getRefresh().setFullInterval(FULL);
        enumeration.getRefresh().setIncrementalInterval(incrementalInterval);
        return EnumerationSettingsResolver.resolve("items", enumeration,
                new ReconciliationProperties.EnumerationDefaults());
    }
}
