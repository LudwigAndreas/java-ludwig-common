package ru.ludwigandreas.webcore.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ru.ludwigandreas.webcore.operation.OperationStatus;

/** The vocabulary itself: which states are terminal, and which terminal states are failures. */
class OperationStatusTest {

    @ParameterizedTest
    @EnumSource(OperationStatus.class)
    @DisplayName("every state answers isTerminal(), and only PENDING and RUNNING say no")
    void everyStateIsClassified(OperationStatus status) {
        boolean stillRunning = status == OperationStatus.PENDING || status == OperationStatus.RUNNING;
        assertThat(status.isTerminal()).isEqualTo(!stillRunning);
    }

    @Test
    @DisplayName("the six constants are exactly the platform vocabulary")
    void theVocabularyIsSixStates() {
        assertThat(OperationStatus.values()).containsExactly(
                OperationStatus.PENDING, OperationStatus.RUNNING, OperationStatus.SUCCEEDED,
                OperationStatus.FAILED, OperationStatus.CANCELLED, OperationStatus.EXPIRED);
    }

    @Test
    @DisplayName("EXPIRED is terminal and is not a failure - retention is not an incident")
    void expiredIsTerminalAndNotAFailure() {
        assertThat(OperationStatus.EXPIRED.isTerminal()).isTrue();
        assertThat(OperationStatus.EXPIRED.isFailure()).isFalse();
    }

    @Test
    @DisplayName("CANCELLED is terminal and is not a failure - it was asked for")
    void cancelledIsTerminalAndNotAFailure() {
        assertThat(OperationStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(OperationStatus.CANCELLED.isFailure()).isFalse();
    }

    @Test
    @DisplayName("FAILED is the only failure, so a client branching on != SUCCEEDED is wrong")
    void failedIsTheOnlyFailure() {
        assertThat(EnumSet.allOf(OperationStatus.class).stream()
                .filter(OperationStatus::isFailure)
                .toList())
                .containsExactly(OperationStatus.FAILED);
    }
}
