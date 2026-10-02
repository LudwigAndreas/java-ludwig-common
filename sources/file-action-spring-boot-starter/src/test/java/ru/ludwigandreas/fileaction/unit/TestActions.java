package ru.ludwigandreas.fileaction.unit;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import ru.ludwigandreas.fileaction.api.ActionMode;
import ru.ludwigandreas.fileaction.api.CommitPolicy;
import ru.ludwigandreas.fileaction.api.ErrorReportFormat;
import ru.ludwigandreas.fileaction.api.ExecutionMode;
import ru.ludwigandreas.fileaction.api.FileActionContext;
import ru.ludwigandreas.fileaction.api.FileActionHandler;
import ru.ludwigandreas.fileaction.api.RowBinding;
import ru.ludwigandreas.fileaction.engine.ResolvedAction;
import ru.ludwigandreas.fileaction.engine.RowMaterialiser;
import ru.ludwigandreas.fileaction.format.SourceFormat;
import ru.ludwigandreas.webcore.preference.UserPreferences;

/** Builds resolved actions for the engine tests. */
final class TestActions {

    private TestActions() {
    }

    static final RowBinding<OrderLine> BINDING = RowBinding.of(OrderLine.class)
            .sheet("Orders")
            .column("sku", "SKU")
            .column("quantity", "Qty")
            .column("price", "Price").optional()
            .column("dueDate", "Due").optional()
            .column("comment", "Comment").optional()
            .build();

    /**
     * A resolved action around a handler.
     *
     * @param handler      the handler
     * @param policy       the commit policy
     * @param batchSize    how many rows one transaction covers
     * @return the action
     */
    static ResolvedAction<OrderLine> action(FileActionHandler<OrderLine> handler, CommitPolicy policy,
                                            int batchSize) {
        return new ResolvedAction<>("order-import", handler, BINDING,
                new RowMaterialiser<>(BINDING, null), ActionMode.DIRECT, ExecutionMode.INLINE, policy,
                Set.of(SourceFormat.XLSX, SourceFormat.CSV), 25L * 1024 * 1024, batchSize, 100, 0.1d,
                Duration.ofMinutes(30), ErrorReportFormat.ANNOTATED_WORKBOOK, null,
                ReadBudgets.generous());
    }

    /** A context for a handler under test. */
    static FileActionContext context() {
        return new FileActionContext(UUID.randomUUID(), "order-import", "orders.xlsx", "corr-1",
                UserPreferences.FALLBACK, false);
    }
}
