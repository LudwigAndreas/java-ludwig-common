package ru.ludwigandreas.notification.service.model;

import java.time.Instant;
import java.util.UUID;

/**
 * An announcement's email fan-out, as the layer above the repository sees it.
 *
 * <p>Carries the platform's {@code OperationStatus} rather than a status of this service's own - there
 * is none, and {@code RuleGroup.OPERATIONS} fails the build on a restatement. The web layer turns this
 * into an {@code OperationResponse} with {@code OperationResponses}; this type exists so that
 * translation happens from a service model rather than from a JPA entity, which the layering rules
 * forbid a controller from touching.
 *
 * @param audienceTotal null until the audience has been walked. The envelope's progress total is
 *                      nullable for exactly this reason: an operation that does not yet know its
 *                      total should say so rather than report a guess that later moves
 */
public record BroadcastRunView(
        UUID id,
        ru.ludwigandreas.webcore.operation.OperationStatus status,
        long deliveriesCreated,
        Long audienceTotal,
        Instant submittedAt,
        Instant startedAt,
        Instant finishedAt,
        String lastError) {

    /** Whether this run has stopped, which decides whether a poll carries a retry hint. */
    public boolean isTerminal() {
        return finishedAt != null;
    }
}
