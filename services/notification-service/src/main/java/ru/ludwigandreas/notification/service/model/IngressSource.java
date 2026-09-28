package ru.ludwigandreas.notification.service.model;

/** Which adapter a request came in through. Recorded for operations, never for authorization. */
public enum IngressSource {
    KAFKA,
    REST
}
