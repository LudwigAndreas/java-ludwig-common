package ru.ludwigandreas.export.api;

import java.util.List;

/**
 * A sheet as a writer sees it: a resolved title and the columns actually being written.
 *
 * <p>The column list is the one that survived the requester's authorities, so a writer never has to
 * know that column visibility exists. That is deliberate: a writer asked to skip columns would be a
 * second place where the visibility rule is applied, and two places is one more than can be kept
 * correct.
 *
 * @param id      the sheet's id, matching {@link SheetDefinition#id()}
 * @param title   the tab caption, <em>already resolved</em> against the run's locale and sanitised
 *                for the format's sheet-name rules
 * @param columns the visible columns, in order
 * @param primary whether this is the sheet a format that can hold only one keeps. Carried here
 *                rather than inferred from position, because a format that keeps one sheet and a
 *                format that keeps all of them must otherwise disagree about the order: making
 *                "the primary one" mean "the first one" would force every multi-sheet file to lead
 *                with it whether or not that is the order the report reads in
 */
public record SheetSpec(String id, String title, List<ColumnSpec> columns, boolean primary) {

    public SheetSpec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A SheetSpec needs an id");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("SheetSpec " + id + " needs a resolved title");
        }
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException(
                    "SheetSpec " + id + " has no visible columns; a sheet the requester may see none of"
                            + " is not written at all");
        }
        columns = List.copyOf(columns);
    }

    /** The only sheet of a single-sheet report, which is always the primary one. */
    public static SheetSpec single(String id, String title, List<ColumnSpec> columns) {
        return new SheetSpec(id, title, columns, true);
    }
}
