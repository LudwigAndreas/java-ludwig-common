package ru.ludwigandreas.example.catalog.support.i18n;

/**
 * Transport-neutral outcome of a failed operation.
 *
 * <p>Every {@link LocalizedException} declares one. Keeping it an enum rather than an
 * {@code HttpStatus} is what lets the service layer state "this is a conflict" without depending on
 * Spring MVC; the web layer maps the enum to a status code in one exhaustive switch.
 */
public enum ProblemStatus {
    NOT_FOUND,
    CONFLICT,
    UNPROCESSABLE,
    INVALID
}
