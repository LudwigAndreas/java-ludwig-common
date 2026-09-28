package ru.ludwigandreas.notification.service.model;

/** Lifecycle of a request as the business layer knows it. */
public enum RequestState {
    ACCEPTED,
    FANNED_OUT,
    REJECTED
}
