package ru.ludwigandreas.notification.service.lock;

/**
 * The locks this service takes.
 *
 * <p>The lock itself is the platform's - {@code RunLock} in {@code job-core}, table
 * {@code job_run_lock}. Only the <em>names</em> stay here, because a lock name is this service's
 * coordination contract between its own replicas and means nothing to any other service. Promoting
 * them alongside the lock would have made one module's vocabulary into platform vocabulary.
 *
 * <p>Constants rather than literals at the call sites, because a lock name is a coordination
 * contract between replicas: two jobs that were meant to be mutually exclusive and spell their lock
 * differently both run, and the symptom - duplicate digests once in a while - looks like anything
 * but a typo.
 */
public final class LockNames {

    /** Collapsing batched deliveries into digest sends. */
    public static final String DIGEST = "notification-digest";

    /** Deleting settled deliveries, expired claims and closed rate-limit windows. */
    public static final String RETENTION = "notification-retention";

    private LockNames() {
    }
}
