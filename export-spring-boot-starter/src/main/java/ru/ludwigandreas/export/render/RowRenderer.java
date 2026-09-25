package ru.ludwigandreas.export.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import ru.ludwigandreas.export.api.CellRenderer;
import ru.ludwigandreas.export.api.CellValue;
import ru.ludwigandreas.export.api.Column;
import ru.ludwigandreas.export.api.NullPolicy;
import ru.ludwigandreas.export.api.RenderContext;
import ru.ludwigandreas.export.exception.RequiredValueMissingException;
import ru.ludwigandreas.export.i18n.ExportMessages;

/**
 * Turns one domain row into typed cells, for one run's column set.
 *
 * <h2>Everything that can be decided once is decided once</h2>
 *
 * <p>A run at the design point renders twenty-five million cells. Anything done per cell that could
 * have been done per run is paid twenty-five million times, so this class resolves the renderer, the
 * null behaviour and the placeholder text for each column at construction and keeps a flat array of
 * them. In particular the placeholder is a message-source lookup, and doing it per cell would be
 * twenty-five million bundle resolutions for a string that never changes during the run.
 *
 * <p>The instance is built per run and used by whichever thread finished a window. It holds no
 * mutable state, so it is safe to share across the windows of one run, which is what the engine
 * does; it is not shared across runs, because the locale, the timezone and the visible column set
 * all belong to the run.
 */
public final class RowRenderer<R> {

    private final List<Slot<R>> slots;
    private final RenderContext context;

    /**
     * Resolves the per-column decisions for one run.
     *
     * @param columns  the columns that survived the requester's authorities, in order
     * @param context  the run's locale and timezone
     * @param messages resolves a placeholder column's text, once
     */
    public RowRenderer(List<Column<R, ?>> columns, RenderContext context, ExportMessages messages) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("A RowRenderer needs at least one column");
        }
        this.context = context;
        List<Slot<R>> resolved = new ArrayList<>(columns.size());
        for (Column<R, ?> column : columns) {
            resolved.add(slotFor(column, context.locale(), messages));
        }
        this.slots = List.copyOf(resolved);
    }

    /** How many cells a rendered row carries. */
    public int width() {
        return slots.size();
    }

    /**
     * Renders one row.
     *
     * @param row       the domain row
     * @param rowNumber which row of the run this is, used only to locate a required-value failure
     * @return the cells, in column order
     * @throws RequiredValueMissingException when a {@link NullPolicy#FAIL} column had no value
     */
    public List<CellValue> render(R row, long rowNumber) {
        return render(row, rowNumber, Map.of());
    }

    /**
     * Renders one row, substituting a marker for the columns of stages that could not answer.
     *
     * <p>The substitution happens here rather than in the engine because this is the one place that
     * already knows which column belongs to which stage. A degraded stage therefore marks exactly
     * its own cells - not every cell of the row, and not none of them - which is what lets a reader
     * see that the customer name is unavailable while the order number beside it is fine.
     *
     * @param row       the domain row
     * @param rowNumber which row of the run this is
     * @param markers   stage name to the cell its columns get instead of a value; usually empty
     * @return the cells, in column order
     */
    public List<CellValue> render(R row, long rowNumber, Map<String, CellValue> markers) {
        List<CellValue> cells = new ArrayList<>(slots.size());
        for (Slot<R> slot : slots) {
            CellValue marker = slot.requiredStage() == null ? null : markers.get(slot.requiredStage());
            cells.add(marker != null ? marker : slot.render(row, rowNumber, context));
        }
        return cells;
    }

    @SuppressWarnings("unchecked")
    private static <R> Slot<R> slotFor(Column<R, ?> column, Locale locale, ExportMessages messages) {
        CellRenderer<Object> renderer = column.getRenderer() == null
                ? DefaultCellRenderers.resolve(column.getFormat())
                : (CellRenderer<Object>) column.getRenderer();
        CellValue absent = switch (column.getNullPolicy()) {
            case EMPTY, FAIL -> CellValue.Empty.INSTANCE;
            case ZERO -> DefaultCellRenderers.zero(column.getFormat());
            case PLACEHOLDER -> new CellValue.Text(
                    messages.resolve("ludwig.export.cell.placeholder", locale));
        };
        return new Slot<>(column.getId(), column.getRequiredStage(),
                (Function<R, Object>) column.getExtractor(), renderer, column.getNullPolicy(), absent);
    }

    /**
     * One column's rendering decisions, flattened.
     *
     * @param columnId      the column, for a required-value failure
     * @param requiredStage the stage that feeds this column, or null for a column read from the row
     * @param extractor     reads the value out of a row
     * @param renderer      turns a present value into a cell
     * @param nullPolicy    what an absent value means here
     * @param absent        the cell written for an absent value, resolved once
     */
    private record Slot<R>(String columnId, String requiredStage, Function<R, Object> extractor,
                           CellRenderer<Object> renderer, NullPolicy nullPolicy, CellValue absent) {

        CellValue render(R row, long rowNumber, RenderContext context) {
            Object value = extractor.apply(row);
            if (value == null) {
                if (nullPolicy == NullPolicy.FAIL) {
                    throw new RequiredValueMissingException(columnId, rowNumber);
                }
                return absent;
            }
            return renderer.render(value, context);
        }
    }
}
