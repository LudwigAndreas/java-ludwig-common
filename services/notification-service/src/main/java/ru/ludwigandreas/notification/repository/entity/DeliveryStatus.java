package ru.ludwigandreas.notification.repository.entity;

/**
 * Persistence-layer lifecycle of one {@link NotificationDeliveryEntity}.
 *
 * <p>Retry state, attempts and failures live on the delivery, never on the request, which is the
 * whole reason the two are separate aggregates: a request addressed to three recipients where one
 * mailbox bounces has to be representable, and it is only representable if each recipient-channel
 * pair carries its own status.
 *
 * <p>Its service-layer twin is {@code service.model.DeliveryState} and its wire twin is
 * {@code web.dto.DeliveryStatusDto}; MapStruct maps between them by constant name at compile time,
 * so adding a state to one without the others fails the build.
 */
public enum DeliveryStatus {

    /**
     * The row exists, but the recipient has not been resolved and preferences have not been
     * evaluated yet. Never claimable. Deliveries are created in this state and settled out of it
     * inside the same fan-out transaction, so it is momentary in wall-clock terms and permanent in
     * the status-history trail - which is where it earns its place.
     */
    ACCEPTED,

    /** Resolved, permitted, and eligible for the poller as soon as {@code next_attempt_at} is due. */
    PENDING,

    /** Leased by exactly one poller instance ({@code claimed_by}/{@code claimed_at}). */
    CLAIMED,

    /** Handed to the provider, which accepted it. Terminal unless a receipt arrives. */
    SENT,

    /** A provider receipt confirmed final delivery. Terminal. */
    DELIVERED,

    /** The attempt failed retryably; {@code next_attempt_at} carries the backoff. Claimable again. */
    FAILED,

    /** Retries exhausted, or the failure was terminal on the first attempt. Terminal. */
    DEAD,

    /** The recipient opted out, is on the suppression list, or the channel is off. Terminal. */
    SUPPRESSED,

    /** An operator cancelled a delivery that had not been sent. Terminal. */
    CANCELLED,

    /**
     * Held for its digest window rather than sent immediately. Never claimable by the dispatch
     * poller - only the digest job moves it out.
     *
     * <p>Not in the lifecycle the brief specified, and added deliberately: without a state that is
     * "real, but not eligible for dispatch", a notification waiting to be collapsed is
     * indistinguishable from one the poller simply has not reached yet.
     */
    BATCHED,

    /** Folded into a digest delivery, which now carries the send. Terminal. */
    COLLAPSED
}
