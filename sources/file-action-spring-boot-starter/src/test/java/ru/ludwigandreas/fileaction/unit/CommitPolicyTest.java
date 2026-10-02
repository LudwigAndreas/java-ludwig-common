package ru.ludwigandreas.fileaction.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ludwigandreas.fileaction.api.CommitPolicy;
import ru.ludwigandreas.fileaction.api.DocumentHandler;
import ru.ludwigandreas.fileaction.api.FileActionContext;
import ru.ludwigandreas.fileaction.api.RowBinding;
import ru.ludwigandreas.fileaction.api.RowHandler;
import ru.ludwigandreas.fileaction.api.RowOutcome;
import ru.ludwigandreas.fileaction.engine.ApplyOutcome;
import ru.ludwigandreas.fileaction.engine.ApplyPass;
import ru.ludwigandreas.fileaction.engine.BoundRowStore;
import ru.ludwigandreas.fileaction.engine.RejectCollector;
import ru.ludwigandreas.fileaction.engine.ResolvedAction;

/**
 * The three commit policies, asserted on the transaction boundary as well as on the counts.
 *
 * <p>The boundary is the policy - the three differ in nothing else - so a test that only counted applied rows
 * would pass for all three on a clean file and would not notice two of them being swapped.
 */
class CommitPolicyTest {

    @TempDir
    Path temp;

    private RecordingTransactionManager transactionManager;
    private BoundRowStore boundRows;
    private ApplyPass applyPass;

    @BeforeEach
    void setUp() {
        transactionManager = new RecordingTransactionManager();
        boundRows = new BoundRowStore(new ObjectMapper());
        applyPass = new ApplyPass(boundRows, new TransactionTemplate(transactionManager));
    }

    @Test
    @DisplayName("PER_ROW: one bad row of four hundred is one reject and the rest apply")
    void perRowAppliesTheRest() throws IOException {
        CountingHandler handler = new CountingHandler(sku -> "A-14".equals(sku)
                ? RowOutcome.rejected("file-action.constraint-violated") : RowOutcome.applied());
        ResolvedAction<OrderLine> action = TestActions.action(handler, CommitPolicy.PER_ROW, 100);
        RejectCollector rejects = new RejectCollector(100);

        ApplyOutcome outcome = apply(action, rows(400), rejects);

        assertThat(outcome.rowsApplied()).isEqualTo(399);
        assertThat(rejects.rejectedRows()).isEqualTo(1);
        assertThat(rejects.sample())
                .as("a handler's reject has to name a row, or the user cannot find it. The bound-row"
                        + " artifact carries the address for exactly this")
                .singleElement()
                .satisfies(problem -> {
                    assertThat(problem.address().row()).isEqualTo(15);
                    assertThat(problem.address().sheet()).isEqualTo("Orders");
                    assertThat(problem.code()).isEqualTo("file-action.constraint-violated");
                });
        assertThat(transactionManager.rolledBack())
                .as("a reject under PER_ROW must not roll anything back")
                .isZero();
        assertThat(transactionManager.committed()).isEqualTo(4);
    }

    @Test
    @DisplayName("ALL_OR_NOTHING: one bad row of four hundred means nothing is applied")
    void allOrNothingAppliesNothing() throws IOException {
        CountingHandler handler = new CountingHandler(sku -> "A-14".equals(sku)
                ? RowOutcome.rejected("file-action.constraint-violated") : RowOutcome.applied());
        ResolvedAction<OrderLine> action = TestActions.action(handler, CommitPolicy.ALL_OR_NOTHING, 100);

        ApplyOutcome outcome = apply(action, rows(400), new RejectCollector(100));

        assertThat(outcome.rowsApplied()).isZero();
        assertThat(transactionManager.transactionCount())
                .as("ALL_OR_NOTHING is one transaction over every row, whatever the batch size")
                .isEqualTo(1);
        assertThat(transactionManager.rolledBack()).isEqualTo(1);
    }

    @Test
    @DisplayName("ALL_OR_NOTHING with no rejects applies everything in one transaction")
    void allOrNothingCleanFile() throws IOException {
        CountingHandler handler = new CountingHandler(sku -> RowOutcome.applied());
        ResolvedAction<OrderLine> action = TestActions.action(handler, CommitPolicy.ALL_OR_NOTHING, 100);

        ApplyOutcome outcome = apply(action, rows(400), new RejectCollector(100));

        assertThat(outcome.rowsApplied()).isEqualTo(400);
        assertThat(transactionManager.outcomes())
                .containsExactly(RecordingTransactionManager.Outcome.COMMITTED);
    }

    @Test
    @DisplayName("PER_BATCH: a failure in the second batch keeps the first and stops")
    void perBatchKeepsEarlierBatches() throws IOException {
        CountingHandler handler = new CountingHandler(sku -> "A-640".equals(sku)
                ? RowOutcome.rejected("file-action.constraint-violated") : RowOutcome.applied());
        ResolvedAction<OrderLine> action = TestActions.action(handler, CommitPolicy.PER_BATCH, 500);

        ApplyOutcome outcome = apply(action, rows(1000), new RejectCollector(100));

        assertThat(outcome.rowsApplied())
                .as("rows 1-500 committed; the batch containing row 640 rolled back")
                .isEqualTo(500);
        assertThat(outcome.failedBatch())
                .as("the user cannot tell which rows to re-send without this")
                .isEqualTo(2);
        assertThat(transactionManager.outcomes()).containsExactly(
                RecordingTransactionManager.Outcome.COMMITTED,
                RecordingTransactionManager.Outcome.ROLLED_BACK);
    }

    @Test
    @DisplayName("PER_BATCH and PER_ROW open the same number of transactions and differ in the rollback")
    void perBatchAndPerRowShareABoundary() throws IOException {
        CountingHandler rejecting = new CountingHandler(sku -> "A-3".equals(sku)
                ? RowOutcome.rejected("file-action.constraint-violated") : RowOutcome.applied());
        ResolvedAction<OrderLine> perRow = TestActions.action(rejecting, CommitPolicy.PER_ROW, 10);
        apply(perRow, rows(10), new RejectCollector(100));
        long perRowRollbacks = transactionManager.rolledBack();

        setUp();
        ResolvedAction<OrderLine> perBatch = TestActions.action(rejecting, CommitPolicy.PER_BATCH, 10);
        apply(perBatch, rows(10), new RejectCollector(100));

        assertThat(perRowRollbacks).isZero();
        assertThat(transactionManager.rolledBack()).isEqualTo(1);
    }

    @Test
    @DisplayName("a skipped row is neither applied nor a reject")
    void skippedRowsAreNeither() throws IOException {
        CountingHandler handler = new CountingHandler(sku -> "A-5".equals(sku)
                ? RowOutcome.skipped("file-action.constraint-violated") : RowOutcome.applied());
        ResolvedAction<OrderLine> action = TestActions.action(handler, CommitPolicy.PER_ROW, 100);
        RejectCollector rejects = new RejectCollector(100);

        ApplyOutcome outcome = apply(action, rows(10), rejects);

        assertThat(outcome.rowsApplied()).isEqualTo(9);
        assertThat(rejects.rejectedRows())
                .as("a deliberate skip must not push a submission over its reject threshold")
                .isZero();
        assertThat(rejects.skippedRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("a handler that throws ends the batch rather than being treated as a rejected row")
    void aThrowIsNotAReject() throws IOException {
        RowHandler<OrderLine> throwing = new RowHandler<>() {
            @Override
            public RowBinding<OrderLine> binding() {
                return TestActions.BINDING;
            }

            @Override
            public RowOutcome apply(OrderLine row, FileActionContext context) {
                throw new IllegalStateException("the database is gone");
            }
        };
        ResolvedAction<OrderLine> action = TestActions.action(throwing, CommitPolicy.PER_ROW, 10);

        assertThatThrownBy(() -> apply(action, rows(10), new RejectCollector(100)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database is gone");
    }

    @Test
    @DisplayName("a handler returning no outcome at all is a defect in the handler, reported as one")
    void aNullOutcomeIsADefect() throws IOException {
        RowHandler<OrderLine> silent = new RowHandler<>() {
            @Override
            public RowBinding<OrderLine> binding() {
                return TestActions.BINDING;
            }

            @Override
            public RowOutcome apply(OrderLine row, FileActionContext context) {
                return null;
            }
        };
        ResolvedAction<OrderLine> action = TestActions.action(silent, CommitPolicy.PER_ROW, 10);

        assertThatThrownBy(() -> apply(action, rows(10), new RejectCollector(100)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must say what it did with the row");
    }

    @Test
    @DisplayName("cancellation is checked between batches, so earlier batches stand")
    void cancellationStopsBetweenBatches() throws IOException {
        CountingHandler handler = new CountingHandler(sku -> RowOutcome.applied());
        ResolvedAction<OrderLine> action = TestActions.action(handler, CommitPolicy.PER_ROW, 100);
        boolean[] cancelled = {false};

        Path bound = rows(400);
        ApplyOutcome outcome = applyPass.apply(action, bound, TestActions.context(),
                new RejectCollector(100), () -> {
                    boolean answer = cancelled[0];
                    cancelled[0] = true;
                    return answer;
                });

        assertThat(outcome.cancelled()).isTrue();
        assertThat(outcome.rowsApplied())
                .as("the batch that was already running completes; the cancel takes effect at the next"
                        + " boundary, which is why the cancel endpoint answers 202")
                .isEqualTo(100);
    }

    @Test
    @DisplayName("a DocumentHandler sees every row in one transaction and reports one applied fact")
    void documentHandlerAppliesOnce() throws IOException {
        List<Integer> seen = new ArrayList<>();
        DocumentHandler<OrderLine> document = new DocumentHandler<>() {
            @Override
            public RowBinding<OrderLine> binding() {
                return TestActions.BINDING;
            }

            @Override
            public RowOutcome apply(Stream<OrderLine> rows, FileActionContext context) {
                seen.add((int) rows.count());
                return RowOutcome.applied();
            }
        };
        ResolvedAction<OrderLine> action =
                TestActions.action(document, CommitPolicy.ALL_OR_NOTHING, 100);

        ApplyOutcome outcome = apply(action, rows(400), new RejectCollector(100));

        assertThat(seen).containsExactly(400);
        assertThat(outcome.rowsApplied())
                .as("one business fact, not four hundred: reporting the row count would tell a client four"
                        + " hundred orders were created when one was")
                .isEqualTo(1);
        assertThat(transactionManager.transactionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a DocumentHandler that refuses the document rolls it back")
    void documentHandlerCanRefuse() throws IOException {
        DocumentHandler<OrderLine> refusing = new DocumentHandler<>() {
            @Override
            public RowBinding<OrderLine> binding() {
                return TestActions.BINDING;
            }

            @Override
            public RowOutcome apply(Stream<OrderLine> rows, FileActionContext context) {
                rows.count();
                return RowOutcome.rejected("file-action.constraint-violated");
            }
        };
        ResolvedAction<OrderLine> action =
                TestActions.action(refusing, CommitPolicy.ALL_OR_NOTHING, 100);

        ApplyOutcome outcome = apply(action, rows(10), new RejectCollector(100));

        assertThat(outcome.rowsApplied()).isZero();
        assertThat(outcome.documentCode()).isEqualTo("file-action.constraint-violated");
        assertThat(transactionManager.rolledBack()).isEqualTo(1);
    }

    private ApplyOutcome apply(ResolvedAction<OrderLine> action, Path bound, RejectCollector rejects) {
        return applyPass.apply(action, bound, TestActions.context(), rejects, () -> false);
    }

    /** Writes {@code count} bound rows to an artifact and returns it. */
    private Path rows(int count) throws IOException {
        Path artifact = temp.resolve("bound-" + System.nanoTime() + ".ndjson");
        try (BoundRowStore.Writer writer = boundRows.writer(artifact)) {
            for (int i = 1; i <= count; i++) {
                // Row i of the sheet is displayed row i + 1, because row 1 is the header.
                writer.write(new ru.ludwigandreas.fileaction.engine.BoundRow<>(
                        "Orders", i + 1, new OrderLine("A-" + i, i, null, null, null)));
            }
        }
        return artifact;
    }

    /** A row handler that applies or rejects according to a predicate on the SKU, and counts its calls. */
    private static final class CountingHandler implements RowHandler<OrderLine> {

        private final java.util.function.Function<String, RowOutcome> decide;

        CountingHandler(java.util.function.Function<String, RowOutcome> decide) {
            this.decide = decide;
        }

        @Override
        public RowBinding<OrderLine> binding() {
            return TestActions.BINDING;
        }

        @Override
        public RowOutcome apply(OrderLine row, FileActionContext context) {
            return decide.apply(row.sku());
        }
    }
}
