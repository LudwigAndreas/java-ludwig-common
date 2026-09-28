package ru.ludwigandreas.db.core.exception;

public class EntityNotFoundException extends RuntimeException {

    public EntityNotFoundException(Class<?> entityType, Object id) {
        super("No " + entityType.getSimpleName() + " found with id " + id);
    }

    public EntityNotFoundException(String message) {
        super(message);
    }
}
