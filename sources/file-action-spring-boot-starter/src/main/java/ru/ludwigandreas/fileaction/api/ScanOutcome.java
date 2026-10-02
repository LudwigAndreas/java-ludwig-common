package ru.ludwigandreas.fileaction.api;

/**
 * What a {@link FileScanner} concluded about a submitted file.
 *
 * @param safe      whether the file may be parsed
 * @param signature what the scanner found, for the audit record and the operator - a malware name, a
 *                  rule id. Never shown to the submitting user, who does not need to know which
 *                  signature matched and should not be told
 * @param scanner   which scanner answered, because an estate usually runs more than one over its life
 *                  and "when did we start seeing this" is the first question after an incident
 */
public record ScanOutcome(boolean safe, String signature, String scanner) {

    /** Rejects an unsafe outcome that does not say what was found. */
    public ScanOutcome {
        if (!safe && (signature == null || signature.isBlank())) {
            throw new IllegalArgumentException(
                    "An unsafe ScanOutcome must name what was found; an unexplained refusal cannot be"
                            + " investigated and cannot be distinguished from a broken scanner");
        }
    }

    /**
     * The file is safe to parse.
     *
     * @param scanner which scanner said so
     * @return the outcome
     */
    public static ScanOutcome safe(String scanner) {
        return new ScanOutcome(true, null, scanner);
    }

    /**
     * The file must not be parsed.
     *
     * @param signature what was found
     * @param scanner   which scanner found it
     * @return the outcome
     */
    public static ScanOutcome unsafe(String signature, String scanner) {
        return new ScanOutcome(false, signature, scanner);
    }
}
