package ru.ludwigandreas.export.exception;

import java.util.List;
import java.util.Set;
import lombok.Getter;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * A format was requested that the definition does not allow.
 *
 * <p>The allowed set travels with the problem rather than only appearing in the sentence, because a
 * client cannot parse a translated sentence and the useful response to this error is to retry with
 * one of the formats that would have worked. The same reasoning applies to every "carries the valid
 * values" code in {@link ExportProblemCodes}.
 */
@Getter
public class FormatNotAllowedException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private final String requestedFormat;
    private final transient Set<String> allowedFormats;

    /**
     * Reports a format the definition does not allow.
     *
     * @param requestedFormat the id that was asked for
     * @param allowedFormats  the ids that would have been accepted, published as {@code allowed}
     */
    public FormatNotAllowedException(String requestedFormat, Set<String> allowedFormats) {
        super(ProblemStatus.INVALID, ExportProblemCodes.FORMAT_NOT_ALLOWED,
                requestedFormat, String.join(", ", allowedFormats.stream().sorted().toList()));
        this.requestedFormat = requestedFormat;
        this.allowedFormats = Set.copyOf(allowedFormats);
        withProperty("format", requestedFormat);
        withProperty("allowed", List.copyOf(allowedFormats.stream().sorted().toList()));
    }
}
