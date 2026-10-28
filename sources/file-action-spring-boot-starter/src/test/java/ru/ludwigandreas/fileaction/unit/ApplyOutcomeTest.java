package ru.ludwigandreas.fileaction.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.fileaction.api.FileActionState;
import ru.ludwigandreas.fileaction.engine.ApplyOutcome;
import ru.ludwigandreas.fileaction.exception.FileActionProblemCodes;
import ru.ludwigandreas.webcore.operation.OperationStatus;

/** Which state a finished apply pass ends a submission in. */
class ApplyOutcomeTest {

    @Test
    @DisplayName("a pass that stopped on a cancel ends CANCELLED, not APPLIED, with no failure code")
    void stopIsCancelled() {
        ApplyOutcome nothingApplied = new ApplyOutcome(0, true, null, null);
        ApplyOutcome someApplied = new ApplyOutcome(40, true, null, null);

        assertThat(nothingApplied.endState()).isEqualTo(FileActionState.CANCELLED);
        assertThat(someApplied.endState()).isEqualTo(FileActionState.CANCELLED);
        assertThat(someApplied.failureCode()).isNull();
        assertThat(FileActionState.CANCELLED.status()).isEqualTo(OperationStatus.CANCELLED);
        assertThat(FileActionState.CANCELLED.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("a stop wins over a failed batch: the caller asked for it, so it is not reported as a failure")
    void stopWinsOverFailedBatch() {
        ApplyOutcome stoppedAfterRollback = new ApplyOutcome(0, true, 2, null);

        assertThat(stoppedAfterRollback.endState()).isEqualTo(FileActionState.CANCELLED);
        assertThat(stoppedAfterRollback.failureCode()).isNull();
    }

    @Test
    @DisplayName("the outcomes that are not stops keep their states and codes")
    void otherOutcomesUnchanged() {
        assertThat(new ApplyOutcome(10, false, null, null).endState()).isEqualTo(FileActionState.APPLIED);
        assertThat(new ApplyOutcome(10, false, 3, null).endState()).isEqualTo(FileActionState.APPLIED);
        ApplyOutcome rolledBack = new ApplyOutcome(0, false, 1, null);
        assertThat(rolledBack.endState()).isEqualTo(FileActionState.REJECTED);
        assertThat(rolledBack.failureCode()).isEqualTo(FileActionProblemCodes.CONSTRAINT_VIOLATED);
        ApplyOutcome refused = new ApplyOutcome(0, false, null, "duplicate-order");
        assertThat(refused.endState()).isEqualTo(FileActionState.REJECTED);
        assertThat(refused.failureCode()).isEqualTo("duplicate-order");
    }
}
