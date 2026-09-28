package ru.ludwigandreas.notification.repository.entity;

/**
 * Why the last attempt failed, and therefore whether there will be another one.
 *
 * <p>Stored rather than re-derived: the classification is made by the channel that knows its
 * provider's error vocabulary, and by the time an operator reads the row the exception is long
 * gone. Without it, "why is this DEAD after one attempt?" is unanswerable.
 */
public enum FailureKind {

    /** Timeout, connection reset, 5xx, SMTP 4yz. Worth another attempt after backoff. */
    RETRYABLE,

    /** Malformed address, 4xx, SMTP 5yz, unknown recipient. Retrying cannot change the outcome. */
    TERMINAL
}
