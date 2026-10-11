package ru.ludwigandreas.reconciliation.entity;

/**
 * Which kind of walk a scope is in the middle of.
 *
 * <p>Persisted so that a walk interrupted by a restart resumes as the kind it started as. A full walk
 * resumed as an incremental one would finish, look complete, and never have listed the members that
 * did not change - and a sweep trusting it would report every one of them as gone.
 */
public enum ScopeWalkKind {

    /** Lists every member of the scope. The only kind that can show a member is gone. */
    FULL,

    /** Lists only what changed since the scope's watermark. Proves nothing about absence. */
    INCREMENTAL
}
