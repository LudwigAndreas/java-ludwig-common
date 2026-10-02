package ru.ludwigandreas.fileaction.engine;

import java.util.List;
import ru.ludwigandreas.fileaction.entity.FileActionRowRejectEntity;

/**
 * One stored reject, as the engine hands it to the web layer.
 *
 * <p>A flat record rather than the entity, for the reason {@link SubmissionSnapshot} gives: a detached JPA entity
 * in a controller is a lazy-loading failure waiting for somebody to add a field, and this one carries a
 * {@code ManyToOne} back to the submission, which is exactly the association that would throw.
 *
 * <p>The message is still a code and its arguments. Rendering happens in the web layer, in the locale of whoever
 * is reading - a reject stored as a sentence would be frozen in whatever locale the submitting request carried.
 *
 * @param sheet  the sheet, or null for a CSV
 * @param row    the 1-based row number as the spreadsheet displays it
 * @param column the column's header name, or null when the problem is the whole row
 * @param code   the stable message key
 * @param args   the message's arguments, in order
 */
public record RejectView(String sheet, int row, String column, String code, List<String> args) {

    /** Defensively copies the argument list. */
    public RejectView {
        args = args == null ? List.of() : List.copyOf(args);
    }

    /**
     * Reads a stored reject.
     *
     * @param reject the row
     * @return the view
     */
    public static RejectView of(FileActionRowRejectEntity reject) {
        return new RejectView(reject.getSheet(), reject.getDisplayedRow(), reject.getColumnHeader(),
                reject.getCode(), reject.argumentList());
    }
}
