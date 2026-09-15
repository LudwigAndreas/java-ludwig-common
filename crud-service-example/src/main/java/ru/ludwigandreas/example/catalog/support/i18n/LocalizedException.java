package ru.ludwigandreas.example.catalog.support.i18n;

import java.util.Arrays;
import lombok.Getter;

/**
 * Base class for every failure this service reports to a client.
 *
 * <p>It carries a message <em>code</em> and its arguments rather than a formatted string: the text
 * is only resolved in the web layer, against the caller's locale, so the same exception renders in
 * English or Russian without the service layer knowing a locale exists. {@code getMessage()} stays
 * developer-facing (logs, stack traces) and is never sent to a client.
 */
@Getter
public abstract class LocalizedException extends RuntimeException {

    private final ProblemStatus status;
    private final String code;
    private final transient Object[] args;

    protected LocalizedException(ProblemStatus status, String code, Object... args) {
        super(code + " " + Arrays.toString(args));
        this.status = status;
        this.code = code;
        this.args = args;
    }
}
