package ru.ludwigandreas.notification.repository.entity;

/** Which ingress a request arrived through. Recorded for operations, never for authorization. */
public enum NotificationSource {

    /** The Kafka consumer - the primary, fire-and-forget path. */
    KAFKA,

    /** The REST API - synchronous sends and operator-initiated ones. */
    REST
}
