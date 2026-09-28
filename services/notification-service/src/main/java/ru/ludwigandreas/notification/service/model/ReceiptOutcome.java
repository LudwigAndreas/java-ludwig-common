package ru.ludwigandreas.notification.service.model;

/** What a provider callback reports. */
public enum ReceiptOutcome {

    /** It reached the recipient. Advances the delivery to {@code DELIVERED}. */
    DELIVERED,

    /** Permanently undeliverable. Dead-letters the delivery and suppresses the address for good. */
    BOUNCED,

    /** Temporarily undeliverable - full mailbox, greylisting. Suppresses the address for a while. */
    DEFERRED,

    /**
     * The recipient marked it as spam. Dead-letters and suppresses permanently, and deliberately
     * does not distinguish which category caused it: a complaint is about us, not about one email.
     */
    COMPLAINED
}
