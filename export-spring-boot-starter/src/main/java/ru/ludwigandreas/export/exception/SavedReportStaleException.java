package ru.ludwigandreas.export.exception;

import lombok.Getter;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * A saved configuration no longer describes something the definition can produce.
 *
 * <p>The missing thing is named - a column, a format, the report itself - because the person who has
 * to fix it is an administrator looking at a configuration screen, and "this saved report is out of
 * date" without saying which part would send them through every field.
 *
 * <p>This exception is the whole reason saved configurations are validated on load and not only on
 * write. A definition changes at deploy time and a configuration written against the old one does
 * not; failing here is what stops the same configuration from quietly producing a narrower file than
 * it did last month.
 */
@Getter
public class SavedReportStaleException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private final String savedReportName;
    private final String missing;

    /**
     * Reports a configuration that has drifted from its definition.
     *
     * @param savedReportName the configuration, published as {@code savedReport}
     * @param missing         what no longer resolves, published as {@code missing}
     */
    public SavedReportStaleException(String savedReportName, String missing) {
        super(ProblemStatus.CONFLICT, ExportProblemCodes.SAVED_REPORT_STALE, savedReportName, missing);
        this.savedReportName = savedReportName;
        this.missing = missing;
        withProperty("savedReport", savedReportName);
        withProperty("missing", missing);
    }
}
