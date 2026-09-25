package ru.ludwigandreas.export.api;

import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * One sheet of a multi-sheet definition: an id, a title key, and how a row is routed to it.
 *
 * <h2>The contiguity requirement</h2>
 *
 * <p>Rows are routed to sheets by {@link #discriminator}, and the source's order must group a
 * sheet's rows together - in practice, the order has to lead with the same expression the
 * discriminator reads. This is a real constraint and it is stated rather than worked around,
 * because the alternatives are both worse at the design point: buffering rows per sheet means
 * holding the report in memory, and keeping every sheet open in a streaming workbook means holding
 * one flush window per sheet and losing the memory bound the writer exists to provide.
 *
 * <p>The engine checks the constraint as it writes. A row routed to a sheet that has already been
 * closed fails the run naming the sheet, rather than opening it again and producing a workbook with
 * two sheets of the same name.
 *
 * @param <R> the row type
 */
public final class SheetDefinition<R> {

    /** Sheet ids are lowercase, dash- or dot-separated: they name a sheet in the run record. */
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z][a-z0-9]*([.\\-][a-z0-9]+)*$");

    private final String id;
    private final String titleKey;
    private final Function<R, String> discriminator;
    private final boolean primary;

    private SheetDefinition(String id, String titleKey, Function<R, String> discriminator, boolean primary) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "Sheet id must be lowercase dot- or dash-separated segments, was: " + id);
        }
        if (titleKey == null || titleKey.isBlank()) {
            throw new IllegalArgumentException("Sheet " + id + " needs a title message key");
        }
        this.id = id;
        this.titleKey = titleKey;
        this.discriminator = discriminator;
        this.primary = primary;
    }

    /**
     * The only sheet of a single-sheet definition.
     *
     * @param id       the sheet's identifier
     * @param titleKey message key for the sheet's tab caption
     * @param <R>      the row type
     * @return a primary sheet every row is routed to
     */
    public static <R> SheetDefinition<R> single(String id, String titleKey) {
        return new SheetDefinition<>(id, titleKey, null, true);
    }

    /**
     * A sheet of a multi-sheet definition.
     *
     * @param id            the sheet's identifier
     * @param titleKey      message key for the tab caption
     * @param discriminator returns this sheet's id for a row that belongs to it; the engine matches
     *                      the returned value against {@link #id()}
     * @param primary       whether {@link MultiSheetStrategy#PRIMARY_ONLY} keeps this one
     * @param <R>           the row type
     * @return the sheet
     */
    public static <R> SheetDefinition<R> of(String id, String titleKey,
                                            Function<R, String> discriminator, boolean primary) {
        if (discriminator == null) {
            throw new IllegalArgumentException("Sheet " + id + " needs a discriminator");
        }
        return new SheetDefinition<>(id, titleKey, discriminator, primary);
    }

    public String id() {
        return id;
    }

    public String titleKey() {
        return titleKey;
    }

    public boolean isPrimary() {
        return primary;
    }

    /** Whether the row belongs to this sheet. Always true for a single-sheet definition. */
    public boolean accepts(R row) {
        return discriminator == null || id.equals(discriminator.apply(row));
    }

    @Override
    public String toString() {
        return "SheetDefinition(" + id + ")";
    }
}
