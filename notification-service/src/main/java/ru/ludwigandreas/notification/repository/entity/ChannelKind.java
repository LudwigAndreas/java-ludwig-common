package ru.ludwigandreas.notification.repository.entity;

/**
 * The transports a delivery can go out over, as stored.
 *
 * <p>An enum rather than a free string because it is a column the claim query filters and indexes
 * on, and because each constant has a {@code NotificationChannel} bean behind it that has to exist
 * at startup. Adding a transport is adding a constant here, its service-layer and wire twins, and
 * one bean - see "Adding a channel" in the README.
 */
public enum ChannelKind {

    /** SMTP, multipart HTML + plain text. */
    EMAIL,

    /** The internal chat system, over its HTTP API. */
    CHAT,

    /** An HMAC-signed HTTP callback to a URL the recipient registered. */
    WEBHOOK
}
