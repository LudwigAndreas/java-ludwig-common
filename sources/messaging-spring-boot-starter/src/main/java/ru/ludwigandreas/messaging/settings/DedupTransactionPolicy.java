package ru.ludwigandreas.messaging.settings;

/**
 * Whether a container carrying the consumer-side dedup filter also gets a transaction manager.
 *
 * <p>A one-field bean exists so that the question can be answered by whoever is able to answer it. The
 * right answer depends on {@code ludwig.idempotency.kafka.mode}, which is a property of a module this one
 * only optionally depends on; so {@code MessagingDedupAutoConfiguration} answers it when the idempotency
 * starter is present and {@code MessagingAutoConfiguration} falls back to the explicit property when it is
 * not. Passing a {@code boolean} into the builder instead of an {@code ObjectProvider} of somebody else's
 * properties class keeps the builder free of a type it may not be able to load.
 *
 * @param containerTransaction whether to attach the application's transaction manager
 * @param reason              why, in one phrase, for the startup log line - a deployment that finds its
 *                            claims committing outside the listener's transaction needs to see which of
 *                            the three inputs decided that
 */
public record DedupTransactionPolicy(boolean containerTransaction, String reason) {
}
