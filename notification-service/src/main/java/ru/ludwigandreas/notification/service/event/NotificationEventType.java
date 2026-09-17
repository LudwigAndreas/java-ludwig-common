package ru.ludwigandreas.notification.service.event;

/**
 * The lifecycle events other services subscribe to.
 *
 * <p>Published through {@code outbox-spring-boot-starter}, which is what the outbox is for here and
 * the only thing it is used for: an event that must be published if and only if the state change it
 * describes committed. The delivery queue itself is emphatically not an outbox - see the README.
 *
 * <p>String constants rather than an enum because they are a wire contract. A consumer matches on
 * the literal, and an enum would invite a rename that compiles cleanly here and breaks every
 * subscriber.
 */
public final class NotificationEventType {

    /** A provider accepted the message, or a receipt confirmed it arrived. */
    public static final String DELIVERED = "NotificationDelivered";

    /** Retries are exhausted or the failure was terminal; nobody received it. */
    public static final String FAILED = "NotificationFailed";

    /** It was never attempted: opted out, on the suppression list, or outside quiet hours. */
    public static final String SUPPRESSED = "NotificationSuppressed";

    private NotificationEventType() {
    }
}
