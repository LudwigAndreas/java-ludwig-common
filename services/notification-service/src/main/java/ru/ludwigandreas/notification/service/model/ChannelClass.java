package ru.ludwigandreas.notification.service.model;

/**
 * Whether delivery over a transport interrupts a person or waits to be read.
 *
 * <p>This is the dimension the service was missing, and adding {@link ChannelType#IN_APP} is what
 * made its absence visible. Three separate rules in the dispatch path - quiet hours, digest
 * collapsing and the address suppression list - are each justified by the same premise, that
 * delivering a notification reaches somebody who is not asking for it right now. That premise is
 * true of every transport this service shipped before the inbox and false of the inbox, so without
 * this enum the inbox would need a special case in each of those three places plus the three
 * evaluators around them. Six special cases on one constant is a missing dimension, not six
 * exceptions.
 *
 * <h2>The test to apply</h2>
 *
 * <p><b>Does delivery reach somebody who is not asking for it right now?</b> If yes the transport is
 * {@link #INTERRUPTING}; if it stores something the recipient reads when they choose, it is
 * {@link #PASSIVE}.
 *
 * <p><b>No build can check that the answer is right.</b> The classification is a statement about the
 * world rather than about the code, in exactly the way a cache's {@code CachePurpose} is: nothing in
 * bytecode or source text distinguishes a transport that declares itself passive and then pushes at
 * a person. What <em>is</em> checked is that an answer exists at all - the classification is a
 * mandatory constructor argument of {@link ChannelType}, so a transport constant added without one
 * does not compile. That is the strongest available check, and it is the reason this is a
 * constructor argument rather than a lookup table that would have defaulted silently.
 *
 * <p>Two values and deliberately not three. A third class meaning "interrupting but not
 * time-sensitive" was considered and dropped: the three behaviours that key off this dimension are
 * quiet hours, digest and address suppression, and no transport wants a combination of them that the
 * two classes below do not already give. A dimension whose third member is hypothetical is a
 * dimension that gets misused.
 *
 * <p>Service-local on purpose. {@code web-core} has no channel vocabulary and must not grow one;
 * nothing outside this service has a transport to classify.
 */
public enum ChannelClass {

    /**
     * Delivery reaches a person who is not asking for it: email, chat, a webhook to a machine that
     * acts on it. Quiet hours, digest collapsing and the address suppression list all apply.
     */
    INTERRUPTING,

    /**
     * Delivery stores something the recipient reads when they choose, and reaching them is their
     * decision rather than ours.
     *
     * <p>None of the three interruption rules applies, and each is switched off for its own reason
     * rather than as a group: a quiet window exists so that nobody is woken and an item that waits
     * wakes nobody; a digest exists to reduce a count of interruptions, and folding an inbox is the
     * inbox's own presentation decision; and a suppression entry is a fact about a delivery address,
     * of which a passive transport has none. Per-category opt-out still applies in full - a
     * recipient may decline a declinable category here exactly as anywhere else.
     */
    PASSIVE
}
