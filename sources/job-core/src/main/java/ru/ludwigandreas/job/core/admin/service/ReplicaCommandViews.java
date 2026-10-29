package ru.ludwigandreas.job.core.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ru.ludwigandreas.webcore.operation.OperationResponse;

/**
 * Replicas and commands as the {@code replicas} area answers them.
 */
public final class ReplicaCommandViews {

    private ReplicaCommandViews() {
    }

    /**
     * One live replica.
     *
     * @param owner      its owner id
     * @param startedAt  when it first heartbeated
     * @param lastSeenAt its last heartbeat
     */
    public record InstanceView(String owner, Instant startedAt, Instant lastSeenAt) {
    }

    /**
     * One targeted replica's answer.
     *
     * @param owner          the replica
     * @param state          {@code SUCCEEDED}, {@code FAILED}, {@code PENDING}, or {@code UNACKNOWLEDGED} once the
     *                       deadline passed without an answer
     * @param failureCode    why it failed, or null
     * @param acknowledgedAt when it answered, or null
     * @param result         the control's per-replica result, or null
     */
    public record TargetView(String owner, String state, String failureCode, Instant acknowledgedAt,
                             JsonNode result) {
    }

    /**
     * A command and its acknowledgements.
     *
     * @param id                the command id
     * @param control           the control
     * @param operation         the operation that authorized it
     * @param submittedBy       who submitted it
     * @param submittedAt       when
     * @param deadline          when silent targets stop counting as pending
     * @param targets           one entry per replica live at submission
     * @param operationEnvelope the command as an operation
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a record's canonical constructor, named at its one construction site.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public record CommandView(UUID id, String control, String operation, String submittedBy, Instant submittedAt,
                              Instant deadline, List<TargetView> targets, OperationResponse operationEnvelope) {
    }
}
