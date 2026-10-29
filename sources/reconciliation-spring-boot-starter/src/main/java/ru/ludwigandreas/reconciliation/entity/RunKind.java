package ru.ludwigandreas.reconciliation.entity;

/** What started a reconciliation run. */
public enum RunKind {

    /** The task's schedule. */
    SCHEDULED,

    /** An operator's run of the task's pass. */
    MANUAL,

    /** An operator's fetch-and-compare, which writes nothing but the run record. */
    DRY_RUN,

    /** An operator's fetch of an explicit set of keys. */
    BACKFILL
}
