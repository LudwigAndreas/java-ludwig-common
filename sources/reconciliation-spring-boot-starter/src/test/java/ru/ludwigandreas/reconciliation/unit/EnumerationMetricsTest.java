package ru.ludwigandreas.reconciliation.unit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.reconciliation.metrics.MicrometerReconciliationMetrics;
import ru.ludwigandreas.reconciliation.metrics.NoopReconciliationMetrics;
import ru.ludwigandreas.reconciliation.metrics.ReconciliationMetrics;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** The enumeration meters, by the names and tags the README documents. */
class EnumerationMetricsTest {

    private static final String TASK = "items";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ReconciliationMetrics metrics = new MicrometerReconciliationMetrics(registry);

    @Test
    void aScopeWalkIsTimedByKindAndOutcome() {
        metrics.recordScopeWalk(TASK, "full", "completed", Duration.ofMillis(40));

        assertThat(registry.get("reconciliation.scope.walk")
                .tags("task", TASK, "kind", "full", "outcome", "completed").timer().count()).isEqualTo(1);
    }

    @Test
    void memberOutcomesAreCountedByOutcome() {
        metrics.recordMemberOutcome(TASK, "applied");
        metrics.recordMemberOutcome(TASK, "applied");
        metrics.recordMemberOutcome(TASK, "left");

        assertThat(registry.get("reconciliation.members.settled")
                .tags("task", TASK, "outcome", "applied").counter().count()).isEqualTo(2);
        assertThat(registry.get("reconciliation.members.settled")
                .tags("task", TASK, "outcome", "left").counter().count()).isEqualTo(1);
    }

    @Test
    void aRemovalTheIntegrationDidNothingAboutHasItsOwnCounter() {
        metrics.recordMemberLeftUnhandled(TASK);

        assertThat(registry.get("reconciliation.members.left_unhandled").tags("task", TASK).counter().count())
                .isEqualTo(1);
    }

    @Test
    void theGaugesArePulledFromTheirSuppliersRatherThanSet() {
        AtomicLong lag = new AtomicLong(0);
        AtomicLong quarantined = new AtomicLong(0);
        AtomicLong held = new AtomicLong(0);
        metrics.registerScopeFreshnessLag(TASK, lag::get);
        metrics.registerScopeAttention(TASK, quarantined::get, held::get);

        lag.set(600);
        quarantined.set(3);
        held.set(1);

        // Nothing was recorded after the suppliers changed: a gauge that had to be set from inside a
        // walk would still read zero here, and would keep reading its last value after walks stopped.
        assertThat(registry.get("reconciliation.scope.freshness.lag").tags("task", TASK).gauge().value())
                .isEqualTo(600.0);
        assertThat(registry.get("reconciliation.members.quarantined").tags("task", TASK).gauge().value())
                .isEqualTo(3.0);
        assertThat(registry.get("reconciliation.sweep.held").tags("task", TASK).gauge().value()).isEqualTo(1.0);
    }

    @Test
    void anImplementationWrittenBeforeEnumerationsExistedStillWorks() {
        ReconciliationMetrics older = new NoopReconciliationMetrics();

        assertThatCode(() -> {
            older.recordScopeWalk(TASK, "full", "completed", Duration.ZERO);
            older.recordMemberOutcome(TASK, "applied");
            older.recordMemberLeftUnhandled(TASK);
            older.registerScopeFreshnessLag(TASK, () -> 0);
            older.registerScopeAttention(TASK, () -> 0, () -> 0);
        }).doesNotThrowAnyException();
    }
}
