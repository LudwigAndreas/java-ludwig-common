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
    WEBHOOK
}
