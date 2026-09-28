package ru.ludwigandreas.ingest.engine;

import ru.ludwigandreas.ingest.api.FileIngest;
import ru.ludwigandreas.ingest.config.FileIngestProperties;

/**
 * One task's two halves, brought together after the validator has confirmed both exist.
 *
 * <p>The bean and the configuration block are discovered independently - one from the context, one
 * from the properties - and either can be present without the other. Pairing them here, once, after
 * {@code FileIngestConfigurationValidator} has refused any context where they disagree, means nothing
 * downstream has to handle a half-configured task: by the time anything reads this record, both sides
 * are known to be there.
 *
 * @param name     the task name, which both halves agree on
 * @param ingest   the parser and applier
 * @param settings the configuration block
 */
public record RegisteredIngestTask(String name, FileIngest<?> ingest, FileIngestProperties.Task settings) {
}
