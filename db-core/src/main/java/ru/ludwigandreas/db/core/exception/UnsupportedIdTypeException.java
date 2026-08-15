package ru.ludwigandreas.db.core.exception;

/**
 * Thrown by generic id-handling code (e.g. a REST layer converting path variables into entity ids) when it is
 * asked to operate on an id type it does not know how to handle.
 */
public class UnsupportedIdTypeException extends RuntimeException {

    public UnsupportedIdTypeException(Class<?> idType) {
        super("Unsupported id type: " + idType.getName());
    }

    public UnsupportedIdTypeException(String message) {
        super(message);
    }
}
