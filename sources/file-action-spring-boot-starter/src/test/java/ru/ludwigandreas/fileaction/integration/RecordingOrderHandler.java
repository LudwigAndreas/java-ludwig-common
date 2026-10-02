package ru.ludwigandreas.fileaction.integration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import ru.ludwigandreas.fileaction.api.FileAction;
import ru.ludwigandreas.fileaction.api.FileActionContext;
import ru.ludwigandreas.fileaction.api.RowBinding;
import ru.ludwigandreas.fileaction.api.RowHandler;
import ru.ludwigandreas.fileaction.api.RowOutcome;

/**
 * The handler under test: records what it was asked to apply and refuses whatever it is told to.
 *
 * <p>Recording rather than writing to a table, because what these tests assert is <em>which rows reached the
 * handler</em> - that a confirmation applies the previewed rows, that a cancelled run stops, that a reject is
 * isolated. A handler that wrote rows would need a table of its own and the assertions would be one indirection
 * further from the thing under test.
 *
 * <p>The list is a {@code CopyOnWriteArrayList} because the deferred worker calls this from a scheduler thread
 * while a test asserts on the main one.
 */
@FileAction("order-import")
public class RecordingOrderHandler implements RowHandler<OrderLineRow> {

    /** The binding, declared as a constant exactly as a real action declares one. */
    public static final RowBinding<OrderLineRow> BINDING = RowBinding.of(OrderLineRow.class)
            .sheet("Orders")
            .column("sku", "SKU").aliases("Article")
            .column("quantity", "Qty")
            .column("note", "Note").optional()
            .build();

    private final List<OrderLineRow> applied = new CopyOnWriteArrayList<>();

    /** SKUs this handler refuses, so a test can produce a handler-level reject. */
    private volatile Set<String> refuse = Set.of();

    @Override
    public RowBinding<OrderLineRow> binding() {
        return BINDING;
    }

    @Override
    public RowOutcome apply(OrderLineRow row, FileActionContext context) {
        if (refuse.contains(row.sku())) {
            return RowOutcome.rejected("file-action.constraint-violated", "SKU", "refused by the test");
        }
        applied.add(row);
        return RowOutcome.applied();
    }

    /** What has been applied, in order. */
    public List<OrderLineRow> applied() {
        return Collections.unmodifiableList(new ArrayList<>(applied));
    }

    /** Forgets everything, between cases. */
    public void reset() {
        applied.clear();
        refuse = Set.of();
    }

    /**
     * Makes this handler refuse the named SKUs.
     *
     * @param skus the SKUs to refuse
     */
    public void refuse(String... skus) {
        refuse = Set.of(skus);
    }
}
