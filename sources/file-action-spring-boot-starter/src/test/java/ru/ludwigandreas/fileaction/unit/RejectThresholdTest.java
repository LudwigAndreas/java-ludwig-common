package ru.ludwigandreas.fileaction.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.fileaction.api.CommitPolicy;
import ru.ludwigandreas.fileaction.api.RowAddress;
import ru.ludwigandreas.fileaction.engine.RejectCollector;
import ru.ludwigandreas.fileaction.engine.ResolvedAction;
import ru.ludwigandreas.fileaction.format.RowProblem;

/**
 * The "this is the wrong file entirely" guard, and the bound on what is kept.
 *
 * <p>The threshold is a fraction of the rows read rather than a count, because the number of mistakes a file may
 * reasonably contain scales with its size: twelve rejects in four hundred rows is a user who needs an error
 * report, and twelve rejects in fifteen rows is last year's template.
 */
class RejectThresholdTest {

    private static final RowHandlerStub HANDLER = new RowHandlerStub();

    @Test
    @DisplayName("rejects below the threshold do not refuse the submission")
    void belowTheThreshold() {
        ResolvedAction<OrderLine> action = TestActions.action(HANDLER, CommitPolicy.PER_ROW, 100);

        assertThat(action.exceedsRejectThreshold(12, 400))
                .as("three per cent of a four-hundred-row file is a user who needs an error report")
                .isFalse();
    }

    @Test
    @DisplayName("rejects above the threshold refuse the submission as a whole")
    void aboveTheThreshold() {
        ResolvedAction<OrderLine> action = TestActions.action(HANDLER, CommitPolicy.PER_ROW, 100);

        assertThat(action.exceedsRejectThreshold(12, 15))
                .as("eighty per cent of the rows failing means the file is the wrong file, and applying the"
                        + " twenty per cent that parsed is never what anybody wanted")
                .isTrue();
    }

    @Test
    @DisplayName("the threshold is exclusive, so a file exactly at it is accepted")
    void exactlyAtTheThreshold() {
        ResolvedAction<OrderLine> action = TestActions.action(HANDLER, CommitPolicy.PER_ROW, 100);

        assertThat(action.exceedsRejectThreshold(10, 100))
                .as("ten per cent of a hundred rows is the configured limit, not past it")
                .isFalse();
    }

    @Test
    @DisplayName("no rejects and no rows read never trip the threshold")
    void degenerateCases() {
        ResolvedAction<OrderLine> action = TestActions.action(HANDLER, CommitPolicy.PER_ROW, 100);

        assertThat(action.exceedsRejectThreshold(0, 400)).isFalse();
        assertThat(action.exceedsRejectThreshold(0, 0))
                .as("an empty file is refused as empty, which is a different message, so the threshold must"
                        + " not also fire and produce two refusals for one file")
                .isFalse();
    }

    @Test
    @DisplayName("a row with four bad cells counts once, not four times")
    void aRowCountsOnce() {
        RejectCollector collector = new RejectCollector(100);

        collector.rejectRow(List.of(
                problem(14, "SKU"), problem(14, "Qty"), problem(14, "Price"), problem(14, "Due")));

        assertThat(collector.rejectedRows())
                .as("counting a row once per bad cell would push a submission over a threshold expressed as"
                        + " a fraction of the rows read")
                .isEqualTo(1);
        assertThat(collector.sample()).hasSize(4);
    }

    @Test
    @DisplayName("the sample is bounded but the count keeps rising")
    void theSampleIsBoundedAndTheCountIsNot() {
        RejectCollector collector = new RejectCollector(3);

        for (int row = 2; row <= 1000; row++) {
            collector.rejectRow(List.of(problem(row, "SKU")));
        }

        assertThat(collector.sample())
                .as("bounded where the rejects are produced, not where they are written: ten thousand reject"
                        + " objects in the heap before anything decides to keep a hundred is the cost this"
                        + " bound exists to avoid")
                .hasSize(3);
        assertThat(collector.rejectedRows())
                .as("the count is what the threshold and the envelope are computed from, so it is not bounded")
                .isEqualTo(999);
    }

    @Test
    @DisplayName("skips are counted separately and never reach the sample")
    void skipsAreSeparate() {
        RejectCollector collector = new RejectCollector(100);

        collector.skipRow();
        collector.skipRow();

        assertThat(collector.skippedRows()).isEqualTo(2);
        assertThat(collector.rejectedRows()).isZero();
        assertThat(collector.hasRejects()).isFalse();
    }

    @Test
    @DisplayName("an empty problem list is not a reject")
    void anEmptyProblemListIsNotAReject() {
        RejectCollector collector = new RejectCollector(100);

        collector.rejectRow(List.of());

        assertThat(collector.rejectedRows()).isZero();
    }

    private static RowProblem problem(int row, String column) {
        return RowProblem.of(RowAddress.ofCell("Orders", row, column),
                "file-action.cell-not-coercible", column);
    }

    /** A handler that applies everything; these cases are about the threshold, not the handler. */
    private static final class RowHandlerStub
            implements ru.ludwigandreas.fileaction.api.RowHandler<OrderLine> {

        @Override
        public ru.ludwigandreas.fileaction.api.RowBinding<OrderLine> binding() {
            return TestActions.BINDING;
        }

        @Override
        public ru.ludwigandreas.fileaction.api.RowOutcome apply(OrderLine row,
                ru.ludwigandreas.fileaction.api.FileActionContext context) {
            return ru.ludwigandreas.fileaction.api.RowOutcome.applied();
        }
    }
}
