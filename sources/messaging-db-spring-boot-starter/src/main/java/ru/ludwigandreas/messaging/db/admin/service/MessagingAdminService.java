package ru.ludwigandreas.messaging.db.admin.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import ru.ludwigandreas.messaging.db.log.LogStore;
import ru.ludwigandreas.messaging.db.log.LogStore.ConsumerPosition;
import ru.ludwigandreas.messaging.db.log.LogStore.MoveTarget;
import ru.ludwigandreas.messaging.db.log.LogStore.Moved;
import ru.ludwigandreas.messaging.db.log.LogStore.Pause;
import ru.ludwigandreas.security.admin.AdminAudit;
import ru.ludwigandreas.security.admin.AdminInvocation;
import ru.ludwigandreas.security.admin.AdminOutcome;

/**
 * The {@code messaging} area's operations, answered from and applied to the offset and pause tables, never to a
 * replica's in-memory consumer state.
 *
 * <p>A pause and a move need no broadcast: a consumer claims a partition by holding the row lock on its position row
 * for one record's handling, so a pause row is seen by every replica's next claim, and a move taken under the same
 * row lock cannot interleave with a delivery.
 */
public class MessagingAdminService {

    private static final String CONSUMER = "consumer-group";

    private final LogStore store;
    private final Supplier<Set<String>> localGroups;
    private final AdminAudit audit;

    /**
     * Creates the service.
     *
     * @param store       the database log
     * @param localGroups the consumer groups this service's own listeners read as; a shared database also holds
     *                    other services' groups, and those are not this service's to pause or move
     * @param audit       the administrative audit
     */
    public MessagingAdminService(LogStore store, Supplier<Set<String>> localGroups, AdminAudit audit) {
        this.store = store;
        this.localGroups = localGroups;
        this.audit = audit;
    }

    /**
     * Every group's position on every partition, with whether its topic is paused for it.
     *
     * @return the positions, ordered by group, topic and partition
     */
    public List<ConsumerView> consumers() {
        Map<String, Pause> paused = store.pauses().stream()
                .collect(Collectors.toMap(pause -> key(pause.groupId(), pause.topic()), Function.identity()));
        return store.positions().stream()
                .map(position -> ConsumerView.of(position, paused.get(key(position.groupId(), position.topic()))))
                .toList();
    }

    /**
     * Pauses a group on a topic, on every replica.
     *
     * @param groupId the consumer group
     * @param topic   the topic
     * @return the pair's state now
     */
    public PauseState pause(String groupId, String topic) {
        require(groupId, topic);
        AdminInvocation invocation = AdminInvocation.require();
        boolean changed = store.pause(groupId, topic, invocation.principal().subject(),
                invocation.statedReason().orElse(null));
        audit.record(CONSUMER, key(groupId, topic), AdminOutcome.SUCCEEDED,
                Map.of("action", "pause", "changed", changed));
        return state(groupId, topic);
    }

    /**
     * Resumes a group on a topic.
     *
     * @param groupId the consumer group
     * @param topic   the topic
     * @return the pair's state now
     */
    public PauseState resume(String groupId, String topic) {
        require(groupId, topic);
        boolean changed = store.resume(groupId, topic);
        audit.record(CONSUMER, key(groupId, topic), AdminOutcome.SUCCEEDED,
                Map.of("action", "resume", "changed", changed));
        return state(groupId, topic);
    }

    /**
     * Moves a paused group's position on one partition or every partition of a topic.
     *
     * @param groupId   the consumer group
     * @param topic     the topic
     * @param partition one partition, or null for all
     * @param to        {@code earliest}, {@code latest}, {@code offset} or {@code timestamp}
     * @param value     the offset (the next one to deliver) or the ISO-8601 instant, for those two
     * @return each partition's old and new position and what the move skips or replays
     */
    public MoveResult moveOffsets(String groupId, String topic, Integer partition, String to, String value) {
        require(groupId, topic);
        MoveTarget target = targetOf(to, value);
        List<Moved> moved = store.movePosition(groupId, topic, partition, target).orElseThrow(() -> {
            audit.record(CONSUMER, key(groupId, topic), AdminOutcome.REFUSED_BY_STATE, Map.of("action", "move"));
            return MessagingAdminException.notPaused(groupId, topic);
        });
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("action", "move");
        attributes.put("to", target.kind().name());
        attributes.put("partitions", moved.stream()
                .map(each -> each.partition() + ":" + each.from() + "->" + each.to()).toList());
        attributes.put("skipped", moved.stream().mapToLong(Moved::skipped).sum());
        attributes.put("replayed", moved.stream().mapToLong(Moved::replayed).sum());
        audit.record(CONSUMER, key(groupId, topic), AdminOutcome.SUCCEEDED, attributes);
        return new MoveResult(groupId, topic, moved.stream().map(PartitionMove::of).toList());
    }

    private void require(String groupId, String topic) {
        if (!localGroups.get().contains(groupId) || !store.hasPositions(groupId, topic)) {
            throw MessagingAdminException.consumerNotFound(groupId, topic);
        }
    }

    private PauseState state(String groupId, String topic) {
        return store.pauses().stream()
                .filter(pause -> pause.groupId().equals(groupId) && pause.topic().equals(topic))
                .findFirst()
                .map(pause -> new PauseState(groupId, topic, true, pause.pausedBy(), pause.pausedAt(), pause.reason()))
                .orElse(new PauseState(groupId, topic, false, null, null, null));
    }

    private static MoveTarget targetOf(String to, String value) {
        if (to == null) {
            throw MessagingAdminException.invalidTarget("'to' is required");
        }
        String kind = to.trim().toLowerCase(Locale.ROOT);
        try {
            return switch (kind) {
                case "earliest" -> new MoveTarget(MoveTarget.Kind.EARLIEST, 0, null);
                case "latest" -> new MoveTarget(MoveTarget.Kind.LATEST, 0, null);
                case "offset" -> new MoveTarget(MoveTarget.Kind.OFFSET, Long.parseLong(required(value)), null);
                case "timestamp" -> new MoveTarget(MoveTarget.Kind.TIMESTAMP, 0, Instant.parse(required(value)));
                default -> throw MessagingAdminException.invalidTarget("'to' must be earliest, latest, offset or"
                        + " timestamp, was '" + to + "'");
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            throw MessagingAdminException.invalidTarget("'value' is not a valid " + kind + ": " + value);
        }
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw MessagingAdminException.invalidTarget("'value' is required for this target");
        }
        return value.trim();
    }

    private static String key(String groupId, String topic) {
        return groupId + "/" + topic;
    }

    /**
     * One group's position on one partition, and whether the topic is paused for it.
     *
     * @param groupId       the consumer group
     * @param topic         the topic
     * @param partition     the partition
     * @param committedOffset the last handled offset
     * @param headOffset    the newest offset in the partition
     * @param lag           records not yet handled
     * @param attempts      failed attempts at the next record
     * @param nextAttemptAt when the next record is tried again
     * @param paused        whether an operator paused the topic for this group
     * @param pausedBy      who, or null
     * @param pausedAt      when, or null
     */
    public record ConsumerView(String groupId, String topic, int partition, long committedOffset, long headOffset,
                               long lag, int attempts, Instant nextAttemptAt, boolean paused, String pausedBy,
                               Instant pausedAt) {

        static ConsumerView of(ConsumerPosition position, Pause pause) {
            return new ConsumerView(position.groupId(), position.topic(), position.partition(),
                    position.committedOffset(), position.headOffset(), position.lag(), position.attempts(),
                    position.nextAttemptAt(), pause != null, pause == null ? null : pause.pausedBy(),
                    pause == null ? null : pause.pausedAt());
        }
    }

    /**
     * A (group, topic) pair's pause state.
     *
     * @param groupId  the consumer group
     * @param topic    the topic
     * @param paused   whether it is paused
     * @param pausedBy who paused it, or null
     * @param pausedAt when, or null
     * @param reason   why, or null
     */
    public record PauseState(String groupId, String topic, boolean paused, String pausedBy, Instant pausedAt,
                             String reason) {
    }

    /**
     * What a move did.
     *
     * @param groupId    the consumer group
     * @param topic      the topic
     * @param partitions each moved partition
     */
    public record MoveResult(String groupId, String topic, List<PartitionMove> partitions) {
    }

    /**
     * One partition's move. Positions are "last handled".
     *
     * @param partition the partition
     * @param from      the position before
     * @param to        the position after
     * @param skipped   records that will not be delivered
     * @param replayed  records that will be delivered again, unless the consumer's dedup still holds them
     */
    public record PartitionMove(int partition, long from, long to, long skipped, long replayed) {

        static PartitionMove of(Moved moved) {
            return new PartitionMove(moved.partition(), moved.from(), moved.to(), moved.skipped(), moved.replayed());
        }
    }
}
