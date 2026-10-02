package ru.ludwigandreas.fileaction.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One refused row, as the paged rejects endpoint serves it.
 *
 * @param sheet   the sheet, or null for a CSV
 * @param row     the 1-based row number as the spreadsheet displays it, so a user can navigate to it
 * @param column  the column's header name, or null when the problem is the whole row
 * @param code    the stable, non-localised reason code, for a client that wants to branch rather than display
 * @param message the reason, rendered in the locale of the person who uploaded the file
 * @param args    the message's arguments, for a client that renders its own text
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RowRejectResponse(String sheet, int row, String column, String code, String message,
                                List<String> args) {

    /** Defensively copies the argument list. */
    public RowRejectResponse {
        args = args == null ? List.of() : List.copyOf(args);
    }
}
