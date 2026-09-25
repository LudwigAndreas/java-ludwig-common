package ru.ludwigandreas.example.catalog.client.dto;

/** Which lane the notification service should queue this in. */
public enum NotificationPriority {

    /** Reserved for things that are useless if they arrive late. A catalogue change is not one. */
    HIGH,

    /** The default lane. */
    NORMAL,

    /** Behind everything else, for volume that nobody is waiting on. */
    BULK
}
