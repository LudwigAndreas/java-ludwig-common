package ru.ludwigandreas.notification.web.dto;

/**
 * How an announcement's audience is described, as it appears on the wire.
 *
 * <p>Separate from the service enum for the same reason {@code ChannelTypeDto} is: the published API
 * contract may outlive an internal rename, and MapStruct proves at compile time that the two still
 * line up.
 *
 * <p>Which of these a deployment permits is configuration, so a caller naming one that is allowed
 * here and not there is refused at publish - and refused identically to an unpermitted role, so that
 * neither answer can be used to probe the configuration.
 */
public enum AudienceTypeDto {

    /** Every user. {@code audienceValue} must be absent. */
    EVERYONE,

    /** Everybody currently holding the role named in {@code audienceValue}. */
    ROLE
}
