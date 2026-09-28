package ru.ludwigandreas.reconciliation.exception;

import java.util.List;

/**
 * The configuration is individually valid and jointly wrong, and the context must not start.
 *
 * <p>Carries every problem found rather than the first, because a person fixing a configuration file
 * at deploy time should get the whole list in one pass instead of discovering them one restart at a
 * time.
 */
public class ReconciliationConfigurationException extends ReconciliationException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param problems every problem found, each stating what breaks rather than what is wrong
     */
    public ReconciliationConfigurationException(List<String> problems) {
        super("ludwig.reconciliation configuration is unsafe:\n  - " + String.join("\n  - ", problems));
    }
}
