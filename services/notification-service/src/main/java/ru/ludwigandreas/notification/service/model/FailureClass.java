package ru.ludwigandreas.notification.service.model;

/**
 * Whether a failed attempt will be tried again.
 *
 * <p>Classified by the channel, because only the channel knows its provider's vocabulary: an SMTP
 * 4yz and an HTTP 503 mean "later", an SMTP 5yz and an HTTP 400 mean "never". Getting this wrong in
 * one direction retries a malformed address forty times; in the other it dead-letters a delivery
 * because a server was restarting.
 */
public enum FailureClass {
    RETRYABLE,
    TERMINAL
}
