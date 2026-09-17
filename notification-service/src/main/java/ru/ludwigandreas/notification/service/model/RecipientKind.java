package ru.ludwigandreas.notification.service.model;

/** How a caller identified a recipient. */
public enum RecipientKind {

    /** By subject, to be resolved against the recipient profile and the identity projection. */
    USER,

    /** By literal destination, for addresses this platform has no account for. */
    ADDRESS
}
