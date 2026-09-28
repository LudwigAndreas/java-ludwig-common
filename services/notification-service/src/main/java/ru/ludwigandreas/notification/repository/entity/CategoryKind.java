package ru.ludwigandreas.notification.repository.entity;

/**
 * Whether a category may be switched off by the recipient.
 *
 * <p>This is the distinction that makes "respect the opt-out" safe to implement. A recipient who
 * unsubscribed from marketing must still receive their password reset, and a single boolean
 * "notifications on/off" cannot express that - so the category a request names carries a
 * classification, and only {@link #MARKETING} is subject to per-category opt-out and quiet hours.
 */
public enum CategoryKind {

    /**
     * The recipient asked for this, directly or by using the product: receipts, password resets,
     * security alerts, expiry warnings. Bypasses preferences and quiet hours. Still honours the
     * suppression list, because a hard bounce is a fact about the address, not a preference.
     */
    TRANSACTIONAL,

    /** Anything the recipient can decline: campaigns, newsletters, product announcements. */
    MARKETING
}
