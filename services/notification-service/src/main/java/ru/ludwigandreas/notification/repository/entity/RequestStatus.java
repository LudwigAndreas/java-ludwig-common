package ru.ludwigandreas.notification.repository.entity;

/**
 * Lifecycle of a {@link NotificationRequestEntity} - what a caller asked for, as opposed to what
 * was individually delivered.
 *
 * <p>Deliberately short. A request is accepted and fanned out, or it is rejected; every outcome
 * beyond that belongs to a {@link DeliveryStatus}. Giving the request a "partially failed" state
 * would mean deriving it from its deliveries and keeping the derivation current, which is precisely
 * the coupling the two-aggregate split exists to avoid.
 */
public enum RequestStatus {

    /** Persisted and idempotency-registered, fan-out not yet written. */
    ACCEPTED,

    /** Every delivery row for this request exists and has been settled out of {@code ACCEPTED}. */
    FANNED_OUT,

    /** Nothing was enqueued - no recipient resolved to a usable address on any requested channel. */
    REJECTED
}
