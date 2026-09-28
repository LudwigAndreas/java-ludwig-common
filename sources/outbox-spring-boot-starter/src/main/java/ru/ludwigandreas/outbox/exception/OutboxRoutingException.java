package ru.ludwigandreas.outbox.exception;

public class OutboxRoutingException extends OutboxException {

    public OutboxRoutingException(String message) {
        super(message);
    }
}
