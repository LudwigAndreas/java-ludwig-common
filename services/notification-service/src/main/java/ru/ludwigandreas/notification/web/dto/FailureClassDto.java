package ru.ludwigandreas.notification.web.dto;

/** Whether the last failure of a delivery was worth retrying, as it appears on the wire. */
public enum FailureClassDto {
    RETRYABLE,
    TERMINAL
}
