package ru.ludwigandreas.notification.service.lock;

/**
 * The locks this service takes.
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
