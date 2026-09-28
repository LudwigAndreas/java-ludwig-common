package ru.ludwigandreas.export.api;

/**
 * Turns a column's extracted value into a typed cell.
 *
 * <p>A column rarely declares one. The engine derives a renderer from the column's value type and
 * {@link CellFormat}, which is what keeps a definition to one line per column; this interface is
 * the escape hatch for the column whose value is not any of the shapes a format maps to - an
 * enumerated status that has to become localized text, a composite that has to become one string.
 *
 * <p>Implementations must be pure and thread-safe. The engine calls them from whichever thread
 * finished the window, and a renderer holding per-run state would be shared across concurrent runs
 * in the same context.
 *
 * @param <V> the column's value type
 */
@FunctionalInterface
public interface CellRenderer<V> {

    /**
     * Renders one value.
     *
     * @param value   the extracted value; never null - absence is handled by the column's
     *                {@link NullPolicy} before a renderer is consulted
     * @param context the run's locale and timezone
     * @return the typed cell
     */
    CellValue render(V value, RenderContext context);
}
