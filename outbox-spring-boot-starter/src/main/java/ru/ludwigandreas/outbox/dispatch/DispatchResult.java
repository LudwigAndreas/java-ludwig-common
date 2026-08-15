package ru.ludwigandreas.outbox.dispatch;

/** Outcome of one {@link OutboxDispatcher#dispatch(ru.ludwigandreas.outbox.entity.OutboxMessage)} call. */
public sealed interface DispatchResult {

    record Success() implements DispatchResult {
    }

    record Failure(String reason, boolean retryable) implements DispatchResult {
    }

    static DispatchResult success() {
        return new Success();
    }

    /** @param retryable false sends the message straight to DEAD_LETTER regardless of remaining attempts (e.g. a 400/422 response) */
    static DispatchResult failure(String reason, boolean retryable) {
        return new Failure(reason, retryable);
    }
}
