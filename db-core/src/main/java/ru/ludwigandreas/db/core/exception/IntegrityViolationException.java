package ru.ludwigandreas.db.core.exception;

public class IntegrityViolationException extends RuntimeException {

    public IntegrityViolationException(String message) {
        super(message);
    }

    public IntegrityViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
