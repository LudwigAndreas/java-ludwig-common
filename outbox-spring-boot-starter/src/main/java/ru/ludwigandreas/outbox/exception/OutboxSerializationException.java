package ru.ludwigandreas.outbox.exception;

public class OutboxSerializationException extends OutboxException {

    public OutboxSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
