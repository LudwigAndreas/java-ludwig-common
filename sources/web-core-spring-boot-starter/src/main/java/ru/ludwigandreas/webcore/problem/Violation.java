package ru.ludwigandreas.webcore.problem;

/**
 * One rejected field, as it appears in the {@code violations} member of a validation problem.
 *
 * <p>Returned as a list rather than concatenated into {@code detail} so that a client can highlight
 * the offending input instead of showing one long sentence: {@code field} is stable and
 * machine-readable, {@code message} is already localized by the validator (which the starter wires
 * to the same {@code MessageSource} the problem text comes from).
 *
 * <p>{@code rejectedValue} is populated only when the starter is configured to include it. It is off
 * by default: echoing submitted values back is how a password or a token ends up in an access log.
 *
 * @param code the constraint that failed ({@code NotBlank}, {@code Size}, ...), so a client can
 *             react to the kind of violation without matching on translated text
 */
public record Violation(String field, String message, String code, Object rejectedValue) {

    public static Violation of(String field, String message, String code) {
        return new Violation(field, message, code, null);
    }
}
