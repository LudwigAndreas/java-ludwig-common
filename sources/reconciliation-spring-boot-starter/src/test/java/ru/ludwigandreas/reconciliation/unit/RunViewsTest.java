package ru.ludwigandreas.reconciliation.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.reconciliation.admin.service.RunSnapshot;
import ru.ludwigandreas.reconciliation.admin.service.RunViews;
import ru.ludwigandreas.reconciliation.api.DemandTier;
import ru.ludwigandreas.reconciliation.engine.RunFailureCodes;
import ru.ludwigandreas.reconciliation.entity.RunKind;
import ru.ludwigandreas.reconciliation.metrics.RunOutcome;
import ru.ludwigandreas.webcore.operation.OperationResponse;
import ru.ludwigandreas.webcore.operation.OperationStatus;

/** How a recorded run becomes the platform's operation envelope. */
class RunViewsTest {

    private static final Instant T0 = Instant.parse("2026-10-29T02:00:00Z");

    private static RunSnapshot run(Instant started, Instant finished, RunOutcome outcome, String failureCode) {
        return new RunSnapshot(UUID.randomUUID(), "supplier-sync", DemandTier.HOT, RunKind.SCHEDULED, null, "pod-a",
                "c-1", T0, started, finished, outcome, failureCode, 3, 3, 2, null);
    }

    @Test
    @DisplayName("pending until it starts, running until it finishes")
    void nonTerminal() {
        assertThat(RunViews.statusOf(run(null, null, null, null))).isEqualTo(OperationStatus.PENDING);
        assertThat(RunViews.statusOf(run(T0, null, null, null))).isEqualTo(OperationStatus.RUNNING);
    }

    @Test
    @DisplayName("a successful run succeeded and points at itself as its result")
    void succeeded() {
        OperationResponse envelope = RunViews.toOperation(run(T0, T0.plusSeconds(5), RunOutcome.SUCCESS, null),
                "/admin/v1/reconciliation/tasks/supplier-sync/runs/x");

        assertThat(envelope.status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(envelope.result().href()).isEqualTo("/admin/v1/reconciliation/tasks/supplier-sync/runs/x");
        assertThat(envelope.failure()).isNull();
        assertThat(envelope.detail()).isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("a failed, timed-out or abandoned run is FAILED with its failure code")
    void failed() {
        OperationResponse threw = RunViews.toOperation(
                run(T0, T0.plusSeconds(1), RunOutcome.FAILURE, RunFailureCodes.RUN_FAILED), "/r");
        OperationResponse timedOut = RunViews.toOperation(
                run(T0, T0.plusSeconds(1), RunOutcome.TIMED_OUT, RunFailureCodes.RUN_TIMED_OUT), "/r");
        OperationResponse abandoned = RunViews.toOperation(
                run(T0, T0.plusSeconds(1), RunOutcome.FAILURE, RunFailureCodes.RUN_ABANDONED), "/r");

        assertThat(threw.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(threw.failure().code()).isEqualTo(RunFailureCodes.RUN_FAILED);
        assertThat(timedOut.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(timedOut.failure().code()).isEqualTo(RunFailureCodes.RUN_TIMED_OUT);
        assertThat(abandoned.failure().code()).isEqualTo(RunFailureCodes.RUN_ABANDONED);
        assertThat(threw.result()).isNull();
    }
}
