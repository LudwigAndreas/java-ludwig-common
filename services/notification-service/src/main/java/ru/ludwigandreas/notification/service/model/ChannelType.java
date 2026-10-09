package ru.ludwigandreas.notification.service.model;

/**
 * The transports the business layer knows about, each carrying how it reaches a recipient.
 *
 * <p>Its persistence twin is {@code repository.entity.ChannelKind} and its wire twin is
 * {@code web.dto.ChannelTypeDto}; MapStruct maps between them by constant name at compile time, so
 * adding a channel to one without the others fails the build rather than a request. Do not relax
 * that mapper with a hand-written default or an {@code unmappedTargetPolicy} change to tolerate an
 * unmapped constant: it would convert a build failure into a runtime one, which is the opposite of
 * what the three-enum split buys.
 *
 * <p>{@link ChannelClass} is a <b>mandatory constructor argument</b> rather than something looked up
 * elsewhere, so a transport added here without a classification does not compile. Read
 * {@link ChannelClass} before adding a constant - it states the test to apply and why no build can
 * check that the answer is correct.
 *
 * <p>{@link #IN_APP} is the one transport with no {@code NotificationChannel} implementation, and
 * that is deliberate rather than missing. A transport implementation is contractually forbidden from
 * touching the database, and the inbox <em>is</em> the database; an in-app delivery is therefore
 * settled in the fan-out transaction alongside the suppressed and batched settlements rather than
 * dispatched from the work queue. The queue exists to keep a slow third-party network off a pooled
 * connection, and there is no third-party network here.
 * {@code InAppSettlementIT#noTransportSupportsInApp} fails the build if an implementation ever
 * claims to support it.
 */
public enum ChannelType {

    /** SMTP, multipart HTML + plain text. */
    EMAIL(ChannelClass.INTERRUPTING),

    /** The internal chat system, over its HTTP API. */
    CHAT(ChannelClass.INTERRUPTING),

    /** An HMAC-signed HTTP callback to a URL the recipient registered. */
    WEBHOOK(ChannelClass.INTERRUPTING),

    /**
     * The recipient's in-product inbox: stored, read back by its owner, and settled in the
     * transaction that accepts the request.
     */
    IN_APP(ChannelClass.PASSIVE);

    private final ChannelClass channelClass;

    ChannelType(ChannelClass channelClass) {
        this.channelClass = channelClass;
    }

    /**
     * How delivery over this transport reaches a recipient.
     *
     * <p>Every rule whose justification is that delivery interrupts somebody asks this rather than
     * naming a constant. An ArchUnit rule in this service's own test sources fails the build if a
     * class in the preference path reads {@link #IN_APP} directly, because that is how the one
     * declaration decays back into the six special cases it exists to replace.
     */
    public ChannelClass channelClass() {
        return channelClass;
    }

    /** Whether delivery over this transport waits to be read rather than reaching out. */
    public boolean isPassive() {
        return channelClass == ChannelClass.PASSIVE;
    }
}
