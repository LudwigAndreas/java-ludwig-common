package ru.ludwigandreas.example.catalog.client.dto;

/**
 * Channels this service is willing to ask for.
 *
 * <p>A caller-side copy of the notification service's own enum, deliberately narrowed to the values
 * this service actually sends. A missing value here is a compile error at the call site; an extra one
 * would be a 400 at runtime.
 */
public enum NotificationChannel {

    /** Email. The only channel a catalogue change currently warrants. */
    EMAIL,

    /** Internal chat. */
    CHAT,

    /** An HMAC-signed webhook. */
    WEBHOOK
}
