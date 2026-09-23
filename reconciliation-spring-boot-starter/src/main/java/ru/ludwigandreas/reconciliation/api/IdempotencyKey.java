package ru.ludwigandreas.reconciliation.api;

import java.util.UUID;

/**
 * The key a submission carries so that a partner which honours idempotency keys can recognise a
 * repeat of a request it has already accepted.
 *
 * <p>Generated and committed <em>before</em> the call that uses it, which is the only ordering that
 * makes it useful: a key invented after the response has arrived proves nothing about a request whose
 * response never did. See the write-order rule in {@code RemoteJobSubmitService}.
 *
 * @param value the key, unique per logical submission and stable across retries of that submission
 */
public record IdempotencyKey(String value) {

    /** Rejects a blank key; a partner cannot deduplicate on nothing. */
    public IdempotencyKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("idempotency key must not be blank");
        }
    }

    /** A fresh random key. */
    public static IdempotencyKey random() {
        return new IdempotencyKey(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
