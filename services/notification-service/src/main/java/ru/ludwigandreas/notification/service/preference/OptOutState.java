package ru.ludwigandreas.notification.service.preference;

/**
 * What a recipient has said about one (category, channel) pair - including having said nothing.
 *
 * <p>Three states rather than a boolean, and the third one is load-bearing. Opt-out resolution asks
 * two questions in order: the exact pair, then the blanket opt-out for the channel. A recipient who
 * has declined everything on email <em>except</em> order updates is expressed as
 * {@code (all, EMAIL) = OPTED_OUT} plus {@code (order-updates, EMAIL) = OPTED_IN}, and the specific
 * answer has to be able to override the blanket one <em>in both directions</em>.
 *
 * <p>Collapsed into a boolean, "not set" and "set to false" become the same value, the opt-in
 * disappears, and the recipient silently stops receiving the one category they asked to keep.
 */
public enum OptOutState {

    /** The recipient has expressed nothing; fall through to the next, broader question. */
    UNSET,

    /** An explicit opt-in. Overrides a broader opt-out. */
    OPTED_IN,

    /** An explicit opt-out. */
    OPTED_OUT
}
