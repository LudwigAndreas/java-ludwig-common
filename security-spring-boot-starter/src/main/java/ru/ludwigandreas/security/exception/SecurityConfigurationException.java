package ru.ludwigandreas.security.exception;

/**
 * A misconfiguration that would make the module fail open. Thrown at startup wherever possible, so a
 * service with an unusable security configuration never reaches the point of serving traffic with it.
 */
public class SecurityConfigurationException extends RuntimeException {

    public SecurityConfigurationException(String message) {
        super(message);
    }

    public SecurityConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
