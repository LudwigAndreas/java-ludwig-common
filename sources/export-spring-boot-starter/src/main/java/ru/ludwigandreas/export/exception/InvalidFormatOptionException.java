package ru.ludwigandreas.export.exception;

import lombok.Getter;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * A per-format option was supplied with a value the writer cannot use.
 *
 * <p>Rejected rather than ignored, for the reason stated on
 * {@code ReportWriterFactory.supportedOptions}: an ignored option is the failure that costs the most
 * to diagnose. The requester asked for a semicolon delimiter, received commas, and has no way to
 * tell whether the option was misspelled, unsupported, or overridden by an administrator.
 *
 * <p>The value is published as well as the name, because for this class of error the value is the
 * mistake - {@code delimiter=";;"} and {@code charset=UTF8X} are both about what was sent rather
 * than about which option was used.
 */
@Getter
public class InvalidFormatOptionException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private final String option;
    private final String value;

    /**
     * Reports an unusable option value.
     *
     * @param option the option name, published as {@code option}
     * @param value  what was sent, published as {@code value}
     */
    public InvalidFormatOptionException(String option, String value) {
        super(ProblemStatus.INVALID, ExportProblemCodes.INVALID_FORMAT_OPTION, option, value);
        this.option = option;
        this.value = value;
        withProperty("option", option);
        withProperty("value", value);
    }
}
