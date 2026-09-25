package ru.ludwigandreas.ingest.api;

/**
 * One ingest: how to turn an object into records, and records into rows.
 *
 * <p>Published as a Spring bean annotated {@link IngestTask}. Everything operational lives in
 * configuration - see the annotation for why the line is drawn there.
 *
 * @param <R> the record type, produced by the parser and consumed by the applier
 */
public interface FileIngest<R> {

    /**
     * The task's name.
     *
     * @return a key under {@code ludwig.ingest.tasks}; must match this bean's {@link IngestTask} value
     */
    String name();

    /**
     * Bytes to records.
     *
     * @return the parser
     */
    RecordParser<R> parser();

    /**
     * Records to staging rows.
     *
     * @return the applier
     */
    RecordApplier<R> applier();
}
