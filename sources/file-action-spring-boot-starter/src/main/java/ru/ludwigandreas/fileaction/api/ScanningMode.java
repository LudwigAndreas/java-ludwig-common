package ru.ludwigandreas.fileaction.api;

/**
 * Whether a submitted file must be scanned before it is parsed.
 *
 * <p>Defaults to {@link #REQUIRED}, so a deployment cannot end up with no scanning by accident. That is the
 * whole design: the module ships no scanner, so the only two honest outcomes are "a scanner is configured"
 * and "somebody said out loud that there is none".
 */
public enum ScanningMode {

    /**
     * A {@link FileScanner} bean must exist, and the application does not start without one.
     *
     * <p>The default, and the startup message names the value that turns the requirement off - so the escape
     * is one line of configuration, taken deliberately, rather than a silence nobody notices.
     */
    REQUIRED,

    /**
     * A scanner is used if one is present and nothing happens if it is not.
     *
     * <p>For an environment that genuinely has no scanner and is not pretending otherwise - a developer's
     * machine, an integration test - without changing the code path a production deployment takes.
     */
    OPTIONAL,

    /** No scanner is consulted even if one is present. */
    DISABLED
}
