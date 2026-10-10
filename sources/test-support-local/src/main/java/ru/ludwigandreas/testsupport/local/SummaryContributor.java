package ru.ludwigandreas.testsupport.local;

import java.util.List;

/**
 * A bean of a launched application that has something to say in the start-up summary.
 *
 * <p>The summary prints each contributor's heading and lines after the partner stubs. It exists so
 * that an optional part of the launcher - the mail server is one - can describe itself without the
 * summary having to name its type, which would load a library the service may not have.
 */
public interface SummaryContributor {

    /**
     * The section heading.
     *
     * @return a short title, without indentation
     */
    String heading();

    /**
     * The section's lines.
     *
     * @return the lines, without indentation
     */
    List<String> lines();
}
