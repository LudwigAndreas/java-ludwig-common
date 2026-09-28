package ru.ludwigandreas.example.catalog.client.dto;

/**
 * Whether a notification is one the recipient asked for or one they are entitled to decline.
 *
 * <p>The distinction is the notification service's, and it drives suppression and quiet hours there.
 * A catalogue notification is {@code MARKETING} unless somebody can show it is not: getting this
 * wrong is how a service ends up sending promotional mail through a transactional exemption.
 */
public enum NotificationCategoryClass {

    /** Something the recipient needs in order to act. Not declinable. */
    TRANSACTIONAL,

    /** Something the recipient may decline. */
    MARKETING
}
