package ru.ludwigandreas.usersettings.exception;

import lombok.Getter;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * A stored value could not be turned back into the type its definition declares.
 *
 * <p>Almost always a schema-evolution fault rather than a caller's: a definition's type changed, or
 * a row was written by a build that encoded it differently. The <em>value</em> is never carried into
 * this exception, only the key and the expected type, because a value that failed to parse is still
 * a value and may still be personal data - see the PII discussion in the README.
 *
 * <p>Note where this surfaces. During resolution it does not: a row that cannot be read is skipped
 * and the next layer down supplies the value, because one unreadable row must not take out a whole
 * {@code getAll} for a user who has forty perfectly good ones. It is thrown on the write path, where
 * there is a caller to tell.
 */
@Getter
public class SettingConversionException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private final String settingKey;

    /**
     * Reports a value that could not be encoded or decoded.
     *
     * @param cause kept for the log only; the value that failed to convert is never carried
     */
    public SettingConversionException(String settingKey, String expectedType, Throwable cause) {
        super(ProblemStatus.INVALID, SettingProblemCodes.CONVERSION, cause, settingKey, expectedType);
        this.settingKey = settingKey;
        withProperty("setting", settingKey);
        withProperty("expectedType", expectedType);
    }
}
