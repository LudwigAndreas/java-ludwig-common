package ru.ludwigandreas.ingest.api;

/**
 * Which {@link Checkpoint} shape a parser can produce, and therefore how a run of it resumes.
 *
 * <p>A separate enum rather than a {@code instanceof} on the checkpoint, because the engine has to
 * know the shape <em>before</em> there is a checkpoint to inspect: it is what decides whether the
 * first read of a resumed run is a ranged GET or a full one, and it is stored on the run row so that
 * a resume after a restart does not depend on the parser bean being asked again.
 */
public enum CheckpointKind {

    /**
     * A byte offset is a record boundary; resuming is a ranged GET and costs nothing.
     *
     * <p>Correct only for a format where every record ends at a byte the parser can name - in
     * practice, one record per line. A parser that declares this for a format where it is not true
     * produces a resume that starts mid-record, and the symptom is one corrupt record per restart.
     */
    BYTE_OFFSET,

    /**
     * A byte offset is not a record boundary; resuming re-reads from the start and skips forward.
     *
     * <p>Slower and equally correct. The right declaration for XML, Parquet, fixed-block formats, and
     * anything whose parser needs a header it would not see if it started in the middle.
     */
    RECORD_ORDINAL
}
