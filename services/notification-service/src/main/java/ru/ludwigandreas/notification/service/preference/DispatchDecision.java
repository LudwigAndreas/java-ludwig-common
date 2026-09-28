package ru.ludwigandreas.notification.service.preference;

import java.time.Instant;

/**
 * Whether a delivery may go out, and if not, why - or when instead.
 *
 * <p>Three outcomes rather than a boolean, because "no" and "not yet" are genuinely different
 * answers and collapsing them is lossy in the direction that loses notifications. A marketing message
 * arriving during quiet hours must be deferred; the same message to somebody who opted out must be
 * suppressed; and a boolean forces one of those two to be implemented as the other.
 */
public sealed interface DispatchDecision {

    /** Send it now. */
    record Allowed() implements DispatchDecision {
    }

    /**
     * Send it later.
     *
     * @param notBefore the first instant outside the recipient's quiet window, in their own zone
     */
    record Deferred(Instant notBefore, String reason) implements DispatchDecision {
    }

    /**
     * Never send it.
     *
     * @param reason a closed-set code ({@code opt-out}, {@code quiet-hours}, {@code suppression-list},
     *               {@code channel-disabled}) - safe as a metric tag and as an audit value, which a
     *               free-text sentence would not be
     */
    record Suppressed(String reason) implements DispatchDecision {
    }

    /** Reason codes, so the strings are declared once rather than spelled differently per call site. */
    final class Reasons {

        /** The recipient declined this category on this channel. */
        public static final String OPT_OUT = "opt-out";

        /** The destination is on the suppression list - a bounce, a complaint or an unsubscribe. */
        public static final String SUPPRESSION_LIST = "suppression-list";

        /** Within the recipient's quiet window, and deferral is switched off. */
        public static final String QUIET_HOURS = "quiet-hours";

        /** No usable address for this channel, or the account is inactive. */
        public static final String UNRESOLVABLE = "unresolvable";

        /** The channel is switched off and this notification is not worth holding indefinitely. */
        public static final String CHANNEL_DISABLED = "channel-disabled";

        private Reasons() {
        }
    }

    DispatchDecision ALLOWED = new Allowed();

    static DispatchDecision allowed() {
        return ALLOWED;
    }

    static DispatchDecision deferred(Instant notBefore, String reason) {
        return new Deferred(notBefore, reason);
    }

    static DispatchDecision suppressed(String reason) {
        return new Suppressed(reason);
    }
}
