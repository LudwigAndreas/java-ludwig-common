package ru.ludwigandreas.ingest.exception;

import java.util.List;

/**
 * The module's configuration is individually valid and jointly wrong, so the context does not start.
 *
 * <p>Collected into one message rather than thrown at the first problem, because a service being
 * configured for the first time usually has several and discovering them one restart at a time is a
 * poor use of an afternoon.
 *
 * <p>Thrown during context refresh, where there is no caller, no locale and no response, so it is a
 * plain runtime exception rather than a {@code LocalizedException} - the same reasoning
 * {@code SettingConfigurationException} and {@code SecurityConfigurationException} follow.
 */
public class IngestConfigurationException extends IngestException {

    private static final long serialVersionUID = 1L;

    /**
     * Every problem found, in one message.
     *
     * @param problems what is wrong, one sentence each
     */
    public IngestConfigurationException(List<String> problems) {
        super("File ingest configuration is not usable:\n  - " + String.join("\n  - ", problems));
    }
}
