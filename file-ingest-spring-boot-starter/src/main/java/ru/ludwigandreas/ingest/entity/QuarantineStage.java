package ru.ludwigandreas.ingest.entity;

/**
 * Where a quarantined record failed.
 *
 * <p>Recorded because the two send whoever reads the row to different places. A parse failure is
 * about the file and usually about the partner who produced it; an apply failure is about this
 * service's own staging table and constraints. A single "failed" status would make a shifted column
 * and a violated check constraint look identical in a listing, and the first question anybody asks
 * about a quarantine spike is which of the two it is.
 */
public enum QuarantineStage {

    /** The parser could not turn the bytes into a record. */
    PARSE,

    /** The record parsed and the applier refused it. */
    APPLY,

    /** The record was larger than any batch could hold - see {@code PoisonRecordException}. */
    OVERSIZED
}
