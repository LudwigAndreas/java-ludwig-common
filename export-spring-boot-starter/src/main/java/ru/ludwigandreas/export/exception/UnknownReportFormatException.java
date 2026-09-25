package ru.ludwigandreas.export.exception;

import java.util.List;
import java.util.Set;
import lombok.Getter;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * A format id was requested that no registered writer factory produces, or that the estate has
 * disabled.
 *
 * <p>Distinct from {@link FormatNotAllowedException}, which is about a format that exists and that
 * this definition does not offer. The distinction matters to the caller: one is answered by picking
 * another format, the other by asking an operator why XLSX is switched off.
 */
@Getter
public class UnknownReportFormatException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private final String requestedFormat;
    private final transient Set<String> knownFormats;

    /**
     * Reports a format id nothing produces.
     *
     * @param code           {@link ExportProblemCodes#UNKNOWN_FORMAT} or
     *                       {@link ExportProblemCodes#FORMAT_DISABLED}
     * @param requestedFormat the id that was asked for
     * @param knownFormats   the ids that are available, published as {@code available}
     */
    public UnknownReportFormatException(String code, String requestedFormat, Set<String> knownFormats) {
        super(ProblemStatus.INVALID, code, requestedFormat,
                String.join(", ", knownFormats.stream().sorted().toList()));
        this.requestedFormat = requestedFormat;
        this.knownFormats = Set.copyOf(knownFormats);
        withProperty("format", requestedFormat);
        withProperty("available", List.copyOf(knownFormats.stream().sorted().toList()));
    }
}
