/**
 * The platform's contract for long-running operations: the vocabulary, the envelope, the headers and
 * the builders that produce the responses.
 *
 * <h2>A contract, not a framework</h2>
 *
 * <p>Nothing here persists anything and nothing here is a Spring bean. Four modules of this platform
 * already track runs in tables designed for their own domain, and this package deliberately does not
 * try to replace them - it fixes the words they use, the shape they answer in and the headers they
 * send, and leaves the storage where it already works. That is also what keeps {@code web-core} free
 * of a persistence dependency it must not have.
 *
 * <p>If a future service has no run table of its own and wants a default store, that is a separate
 * {@code operations-spring-boot-starter} and a separate decision. The four existing modules must
 * never be forced onto it.
 *
 * <h2>Where to start</h2>
 *
 * <ul>
 *   <li>{@link ru.ludwigandreas.webcore.operation.OperationStatus} - the six states, and why
 *       {@code EXPIRED} is terminal without being a failure.</li>
 *   <li>{@link ru.ludwigandreas.webcore.operation.OperationResponse} - the envelope.</li>
 *   <li>{@link ru.ludwigandreas.webcore.operation.OperationResponses} - the builders, which refuse
 *       the malformed responses rather than merely shortening the correct ones.</li>
 *   <li>{@link ru.ludwigandreas.webcore.operation.Cancellation} - why cancellation is cooperative
 *       and why a cancel endpoint answers {@code 202}.</li>
 * </ul>
 *
 * <h2>Idempotency, which is next to this and is not this</h2>
 *
 * <p>A submit endpoint for a long-running operation should accept an {@code Idempotency-Key}, so
 * that a retried submit returns the <em>same</em> operation id instead of starting a second run.
 * What it must not do is blur the two codes: {@code 202} means "I accepted new work, here is its
 * id", and the {@code 409 + Retry-After} that
 * {@code ru.ludwigandreas.idempotency.error.ClaimInProgressException} produces means "your duplicate
 * found work already in flight; nothing new was started". A submit endpoint that answered
 * {@code 202} for a duplicate would be telling the caller it had started a second run.
 */
package ru.ludwigandreas.webcore.operation;
