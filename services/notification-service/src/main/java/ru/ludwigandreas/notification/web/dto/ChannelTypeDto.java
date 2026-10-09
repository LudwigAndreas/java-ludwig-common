package ru.ludwigandreas.notification.web.dto;

/**
 * Channel as it appears on the wire.
 *
 * <p>Separate from the service enum on purpose: the published API contract is allowed to outlive an
 * internal rename, and MapStruct proves at compile time that the two still line up.
 */
public enum ChannelTypeDto {
    EMAIL,
    CHAT,
    WEBHOOK,

    /**
     * The recipient's in-product inbox.
     *
     * <p>Requestable like any other channel. A caller naming it gets a delivery that is already
     * terminal in the 202 response, because an in-app notification is settled in the transaction
     * that accepts the request rather than queued for dispatch.
     */
    IN_APP
}
