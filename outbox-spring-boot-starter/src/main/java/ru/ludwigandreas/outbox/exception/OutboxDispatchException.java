package ru.ludwigandreas.outbox.exception;

public class OutboxDispatchException extends OutboxException {

    public OutboxDispatchException(String message) {
        super(message);
    }

    public OutboxDispatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
