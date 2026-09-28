package ru.ludwigandreas.webcore.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.webcore.operation.OperationProgress;

/** The nullable denominator, which is the whole reason this record exists. */
class OperationProgressTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("a null total serializes without a denominator and never as 0")
    void nullTotalIsAbsentRatherThanZero() throws Exception {
        String body = json.writeValueAsString(OperationProgress.of(4_000_000L, "records"));

        assertThat(body).contains("\"completed\":4000000");
        assertThat(body).doesNotContain("total");
        assertThat(body).doesNotContain("\"total\":0");
    }

    @Test
    @DisplayName("percent is null without a denominator rather than 0")
    void percentIsNullWithoutATotal() {
        assertThat(OperationProgress.of(120L, "records").percent()).isNull();
    }

    @Test
    @DisplayName("percent is derived from the two numbers, so it cannot disagree with them")
    void percentIsDerived() {
        assertThat(OperationProgress.of(25L, 100L, "rows").percent()).isEqualTo(25);
        assertThat(OperationProgress.of(100L, 100L, "rows").percent()).isEqualTo(100);
        assertThat(OperationProgress.of(0L, 100L, "rows").percent()).isZero();
    }

    @Test
    @DisplayName("a phase is a label on the same progress, not a second state")
    void phaseIsALabel() {
        OperationProgress progress = OperationProgress.of(10L, "records").inPhase("merging");

        assertThat(progress.phase()).isEqualTo("merging");
        assertThat(progress.completed()).isEqualTo(10L);
        assertThat(progress.total()).isNull();
    }

    @Test
    @DisplayName("a total smaller than what is already done is a producer bug, not a rounding issue")
    void totalBelowCompletedIsRejected() {
        assertThatThrownBy(() -> new OperationProgress(10L, 5L, "rows", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OperationProgress(-1L, null, "rows", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
