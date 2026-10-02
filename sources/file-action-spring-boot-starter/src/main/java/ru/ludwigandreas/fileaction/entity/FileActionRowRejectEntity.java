package ru.ludwigandreas.fileaction.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.ludwigandreas.db.core.entity.GeneratedEntity;

/**
 * One refused row, as the paged rejects endpoint serves it.
 *
 * <h2>A bounded sample, not the full set</h2>
 *
 * <p>The action's {@code reject-sample} caps how many of these are written. The full set lives as an
 * artifact in the object store, because a ten-thousand-reject upload would otherwise put ten thousand rows
 * in a table whose only query is "give me the first page" - and the row that matters to the user is the one
 * in their own file, which the annotated workbook puts there.
 *
 * <h2>The message is a code and its arguments, never rendered text</h2>
 *
 * <p>A reject is written by whichever instance read the file and may be read back by a different person, in
 * a different locale, months later. Storing the rendered sentence would freeze it in whatever language the
 * submitting request happened to carry, and would make a bundle correction unapplicable to anything already
 * stored.
 */
@Entity
@Table(name = "file_action_row_reject")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileActionRowRejectEntity extends GeneratedEntity<UUID> {

    /** Separates the stored message arguments. A newline, because an argument may contain anything else. */
    public static final String ARGS_SEPARATOR = "\n";

    /**
     * The submission this reject belongs to.
     *
     * <p>Lazy, and the only association in the module. Eager would load the whole submission for every row
     * of a page of rejects, which is the n+1 that a rejects endpoint is most likely to hit.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submission_id", nullable = false, updatable = false)
    private FileActionSubmissionEntity submission;

    /** The sheet, or null for a CSV. */
    @Column(name = "sheet", length = 255)
    private String sheet;

    /** The 1-based row number as the spreadsheet displays it, including the header. */
    @Column(name = "displayed_row", nullable = false)
    private int displayedRow;

    /** The column's declared header name, or null when the problem is the whole row. */
    @Column(name = "column_header", length = 255)
    private String columnHeader;

    /** The message key, from {@code FileActionProblemCodes} or from a handler's own vocabulary. */
    @Column(name = "code", nullable = false, length = 128)
    private String code;

    /** The message arguments, separated by {@link #ARGS_SEPARATOR}. */
    @Column(name = "args", columnDefinition = "text")
    private String args;

    /** When the reject was recorded. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * The stored arguments as a list.
     *
     * @return the arguments, in order; empty when there are none
     */
    public List<String> argumentList() {
        if (args == null || args.isEmpty()) {
            return List.of();
        }
        return List.of(args.split(ARGS_SEPARATOR, -1));
    }

    /**
     * Joins arguments for storage.
     *
     * @param arguments the arguments, in order
     * @return the stored form, or null when there are none
     */
    public static String joinArguments(List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return null;
        }
        return String.join(ARGS_SEPARATOR, arguments);
    }
}
