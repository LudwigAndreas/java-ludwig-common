package ru.ludwigandreas.audit;

/**
 * Where the platform's audit trail is written.
 *
 * <p>One method, for the reason {@code ExportAuditSink} gave when it was one of nine: "an audit sink
 * that could be asked questions would be tempting to read from, and a trail that the thing being
 * audited also reads is a trail that will eventually be filtered by it." Reading the trail is the
 * query API in {@code audit-spring-boot-starter}, which is a different type with a different set of
 * callers.
 *
 * <h2>Throwing</h2>
 *
 * <p>An implementation <em>may</em> throw. That is a change from the seven SPIs this replaces, every
 * one of which said "must not throw", and it is deliberate: the old rule made an unrecorded state
 * change silently normal. What decides whether a caller survives a sink failure is
 * {@link AuditFailurePolicy}, applied by {@link FailurePolicyAuditSink}, which every wiring in this
 * platform puts in front of the real sink. An observational event is logged and dropped; a state
 * mutation fails the operation, because a mutation that committed without its audit row is precisely
 * the thing the trail exists to make impossible.
 *
 * <p>An implementation must not <em>block</em>. It is called on the caller's thread, which is a
 * request thread or a Reactor thread as often as it is a scheduler's. A sink that needs to reach the
 * network hands the event to a queue and returns; the shipped answer to that requirement is
 * {@code OutboxAuditSink}, which makes the hand-off a row in the caller's own transaction.
 */
@FunctionalInterface
public interface AuditSink {

    /**
     * Records one event.
     *
     * @param event what happened, with its attributes already redacted
     */
    void record(AuditEvent event);
}
