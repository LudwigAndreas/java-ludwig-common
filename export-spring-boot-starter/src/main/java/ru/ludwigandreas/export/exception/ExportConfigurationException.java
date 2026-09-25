package ru.ludwigandreas.export.exception;

/**
 * The module refuses to start.
 *
 * <p>Every use of this is a mistake that would otherwise be discovered by a user: a definition with
 * a header key that exists in neither bundle, a column fed by a stage nobody declared, a saved
 * configuration pointing at a column that was deleted, a window smaller than the enrichment batch
 * it is supposed to contain. All of them produce a report that is wrong rather than a report that
 * fails, and all of them are cheap to detect before the context finishes starting.
 *
 * <p>Messages list every problem found rather than only the first, because somebody fixing a
 * configuration at deploy time should get the whole list in one pass instead of one redeploy per
 * mistake.
 */
public class ExportConfigurationException extends ExportException {

    private static final long serialVersionUID = 1L;

    public ExportConfigurationException(String message) {
        super(message);
    }

    public ExportConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
