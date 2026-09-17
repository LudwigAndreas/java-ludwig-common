package ru.ludwigandreas.notification.web.dto;

/**
 * Whether the recipient may decline this category, as it appears on the wire.
 *
 * <p>Sent by the caller and deliberately so, because the calling service is the one that knows
 * whether what it is sending is a receipt or a campaign. It is not a free pass: which categories may
 * legitimately be transactional is a matter for review of the notification catalogue, and the
 * category itself - not this flag - is what a recipient's preferences are expressed against.
 */
public enum CategoryClassDto {
    TRANSACTIONAL,
    MARKETING
}
