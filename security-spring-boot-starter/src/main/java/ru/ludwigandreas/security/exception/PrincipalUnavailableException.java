package ru.ludwigandreas.security.exception;

/**
 * Raised by {@code SecurityPrincipals.require()} when business code that needs a caller runs without
 * one - typically a scheduled job or a Kafka listener reusing a service method written for requests.
 *
 * <p>It is an {@link IllegalStateException} rather than an {@code AccessDeniedException} because it
 * is a bug in the calling code, not a rejected caller: turning it into a 403 would hide the defect
 * behind a plausible-looking response.
 */
public class PrincipalUnavailableException extends IllegalStateException {

    public PrincipalUnavailableException() {
        super("No authenticated principal is bound to the current thread. "
                + "If this runs outside a request (scheduler, Kafka listener, @Async), establish a "
                + "system principal explicitly - see SystemPrincipalTemplate.");
    }
}
