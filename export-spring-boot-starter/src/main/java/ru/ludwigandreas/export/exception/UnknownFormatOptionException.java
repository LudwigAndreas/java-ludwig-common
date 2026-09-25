package ru.ludwigandreas.export.exception;

import java.util.List;
import java.util.Set;
import lombok.Getter;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * A request carried an option the chosen format does not accept.
 *
 * <p>The accepted set travels with the problem, because the useful response is to retry with an
 * option that exists, and a client cannot get that out of a translated sentence. See
 * {@link InvalidFormatOptionException} for why an unknown option is an error at all rather than
 * something to skip over.
 */
@Getter
public class UnknownFormatOptionException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private final String option;
    private final String formatId;

    /**
     * Reports an option the format does not understand.
     *
     * @param option    the option name, published as {@code option}
     * @param formatId  the format that was asked for, published as {@code format}
     * @param supported the option names it does accept, published as {@code available}
     */
    public UnknownFormatOptionException(String option, String formatId, Set<String> supported) {
        super(ProblemStatus.INVALID, ExportProblemCodes.UNKNOWN_FORMAT_OPTION,
                option, formatId, String.join(", ", supported.stream().sorted().toList()));
        this.option = option;
        this.formatId = formatId;
        withProperty("option", option);
        withProperty("format", formatId);
        withProperty("available", List.copyOf(supported.stream().sorted().toList()));
    }
}
