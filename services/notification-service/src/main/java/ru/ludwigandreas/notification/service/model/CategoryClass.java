package ru.ludwigandreas.notification.service.model;

/**
 * Whether the recipient is allowed to decline this category.
 *
 * <p>The distinction that makes opt-out implementable: a recipient who unsubscribed from marketing
 * must still get their password reset, and a single on/off switch cannot express that.
 */
public enum CategoryClass {

    /** Bypasses preferences and quiet hours; still honours the suppression list. */
    TRANSACTIONAL,

    /** Subject to opt-out, quiet hours and digesting. */
    MARKETING
}
