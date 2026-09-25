package ru.ludwigandreas.example.catalog.client.dto;

/**
 * Who to notify.
 *
 * <p>Exactly one of {@code userId} and {@code address} must be set, and the notification service
 * rejects a recipient that sets both or neither. Prefer {@code userId}: it lets the recipient's own
 * preferences, locale and current address resolve on that side, where they are owned, instead of this
 * service holding a copy of an address that may be months out of date.
 *
 * @param userId  the platform user id, resolved to an address by the notification service
 * @param address a literal address, for a recipient that is not a platform user
 * @param locale  BCP 47 tag overriding the recipient's own preference; null to let it resolve
 */
public record NotificationRecipient(String userId, String address, String locale) {

    /** A recipient identified by platform user id - the preferred form. */
    public static NotificationRecipient ofUser(String userId) {
        return new NotificationRecipient(userId, null, null);
    }

    /** A recipient identified by a literal address, for a mailbox that belongs to no user. */
    public static NotificationRecipient ofAddress(String address) {
        return new NotificationRecipient(null, address, null);
    }
}
