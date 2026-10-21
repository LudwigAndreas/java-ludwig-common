package ru.ludwigandreas.messaging.db.log;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.utils.Utils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Every statement against the log, and the only class that knows its schema.
 *
 * <p>Each unit of work runs on a connection of its own, taken from the pool and committed or rolled back
 * here, never on a connection bound to the calling thread. That is deliberate: a listener invoked while a
 * partition is claimed opens its own transactions through the application's transaction manager, and a
 * JPA transaction manager refuses to start on a thread that already has a JDBC connection bound to its
 * data source. Keeping the claim's connection unbound is what lets the listener behave exactly as it does
 * under a Kafka container, where the offset commit is not part of the listener's transaction either.
 */
public class LogStore {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final TypeReference<List<Map<String, String>>> HEADER_LIST = new TypeReference<>() {
    };
    private static final int PURGE_BATCH = 5_000;

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final int partitions;
    private final String log;
    private final String head;
    private final String offsets;

    /**
     * Creates the store.
     *
     * @param dataSource   the shared database
     * @param objectMapper encodes the header list
     * @param schema       the schema the log tables live in; interpolated into SQL, so it must be a plain
     *                     lower-case identifier and is refused otherwise
     * @param partitions   the partition count every topic is split into
     */
    public LogStore(DataSource dataSource, ObjectMapper objectMapper, String schema, int partitions) {
        if (schema == null || !IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException("ludwig.messaging.db.schema must be a lower-case SQL identifier, was '"
                    + schema + "'");
        }
        if (partitions < 1) {
            throw new IllegalArgumentException("ludwig.messaging.db.partitions must be at least 1, was " + partitions);
        }
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.partitions = partitions;
        this.log = schema + ".message_log";
        this.head = schema + ".partition_head";
        this.offsets = schema + ".consumer_offset";
    }

    /**
     * The partition a key lands on: Kafka's default partitioner for a keyed record, so a key lands on the
     * same partition number on either transport. A record with no key goes to partition 0.
     *
     * @param key the serialized key, or {@code null}
     * @return the partition
     */
    public int partitionFor(byte[] key) {
        return key == null ? 0 : Utils.toPositive(Utils.murmur2(key)) % partitions;
    }

    /**
     * Appends one record and returns where it landed.
     *
     * <p>The offset is taken from the partition's head row by an upsert that keeps the row locked until
     * this transaction commits. Two writers to one partition are therefore serialized, and a record never
     * becomes visible at an offset lower than one a consumer has already passed - which a sequence would
     * allow, and which would make that record unreachable.
     *
     * @param topic   the topic
     * @param key     the serialized key, or {@code null}
     * @param headers the record's headers
     * @param payload the serialized payload, or {@code null} for a tombstone
     * @return the partition and offset
     */
    public Appended append(String topic, byte[] key, Headers headers, byte[] payload) {
        int partition = partitionFor(key);
        String encodedHeaders = encode(headers);
        return inTransaction(jdbc -> {
            Long offset = jdbc.queryForObject("INSERT INTO " + head + " (topic, log_partition, next_offset)"
                            + " VALUES (?, ?, 1) ON CONFLICT (topic, log_partition)"
                            + " DO UPDATE SET next_offset = " + head + ".next_offset + 1"
                            + " RETURNING next_offset - 1",
                    Long.class, topic, partition);
            Timestamp producedAt = jdbc.queryForObject("INSERT INTO " + log
                            + " (topic, log_partition, log_offset, record_key, headers, payload)"
                            + " VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?) RETURNING produced_at",
                    Timestamp.class, topic, partition, offset, key, encodedHeaders, payload);
            return new Appended(topic, partition, offset, producedAt.toInstant());
        });
    }

    /**
     * Makes sure a group has a position on every partition of the given topics.
     *
     * <p>Positions that already exist are left alone, which is what makes this safe to run on every start
     * of every instance. A new position starts before the first record for {@code earliest} and at the
     * head for {@code latest}, as Kafka's {@code auto.offset.reset} does for a group with no committed
     * offset.
     *
     * @param groupId  the consumer group
     * @param topics   the topics it reads from the log
     * @param earliest whether a new position starts at the beginning
     */
    public void ensurePositions(String groupId, Collection<String> topics, boolean earliest) {
        inTransaction(jdbc -> {
            for (String topic : topics) {
                jdbc.update("INSERT INTO " + head + " (topic, log_partition, next_offset)"
                        + " SELECT ?, p, 0 FROM generate_series(0, ? - 1) p ON CONFLICT DO NOTHING",
                        topic, partitions);
                jdbc.update("INSERT INTO " + offsets + " (group_id, topic, log_partition, committed_offset)"
                        + " SELECT ?, h.topic, h.log_partition, CASE WHEN ? THEN -1 ELSE h.next_offset - 1 END"
                        + " FROM " + head + " h WHERE h.topic = ? ON CONFLICT DO NOTHING",
                        groupId, earliest, topic);
            }
            return null;
        });
    }

    /**
     * Claims one partition that has work for this group, hands its next record to the handler, and
     * applies the handler's outcome to the group's position - all while the partition stays claimed.
     *
     * <p>The claim is the row lock on the group's position row, taken with {@code SKIP LOCKED}, so it is
     * also the partition assignment: an instance that dies mid-record loses its transaction, its lock and
     * nothing else, and the next poll of any instance picks the partition up at the last committed position.
     * One record per claim keeps a crash to one redelivery and keeps the lock short.
     *
     * @param groupId the consumer group
     * @param topics  the topics it reads from the log
     * @param handler decides what happens to the record
     * @return {@code false} when no partition had work, so the caller can wait
     */
    public boolean processNext(String groupId, Collection<String> topics, RecordHandler handler) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            Optional<Position> claimed = claim(jdbc, groupId, topics);
            if (claimed.isEmpty()) {
                connection.rollback();
                return false;
            }
            Position position = claimed.get();
            Optional<StoredRecord> next = next(jdbc, position);
            if (next.isEmpty()) {
                // Everything after the position was purged: skip to the head rather than claim it forever.
                jdbc.update("UPDATE " + offsets + " o SET committed_offset = h.next_offset - 1, attempts = 0"
                        + " FROM " + head + " h WHERE h.topic = o.topic AND h.log_partition = o.log_partition"
                        + " AND o.group_id = ? AND o.topic = ? AND o.log_partition = ?",
                        groupId, position.topic(), position.partition());
                connection.commit();
                return true;
            }
            Outcome outcome;
            try {
                outcome = handler.handle(position, next.get());
            // CHECKSTYLE.OFF: IllegalCatch - whatever the handler threw, the position must not move and the
            // partition must be released; the failure is rethrown, not handled.
            } catch (RuntimeException e) {
            // CHECKSTYLE.ON: IllegalCatch
                connection.rollback();
                throw e;
            }
            apply(jdbc, groupId, position, next.get(), outcome);
            connection.commit();
            return true;
        } catch (SQLException e) {
            throw new LogAccessException("Polling the log failed for group '" + groupId + "'", e);
        }
    }

    /**
     * How far each group's position is behind its partitions' heads, summed per topic.
     *
     * @return one row per (group, topic)
     */
    public List<Lag> lag() {
        return inTransaction(jdbc -> jdbc.query("SELECT o.group_id, o.topic,"
                        + " sum(GREATEST(h.next_offset - 1 - o.committed_offset, 0)) AS lag"
                        + " FROM " + offsets + " o JOIN " + head + " h"
                        + " ON h.topic = o.topic AND h.log_partition = o.log_partition"
                        + " GROUP BY o.group_id, o.topic",
                (rs, row) -> new Lag(rs.getString("group_id"), rs.getString("topic"), rs.getLong("lag"))));
    }

    /**
     * Counts, per (group, topic), the records older than the cutoff that the group has not consumed yet.
     *
     * @param cutoff records produced before this instant are due for purging
     * @return one row per (group, topic) that would lose records
     */
    public List<Lag> unconsumedBefore(Instant cutoff) {
        return inTransaction(jdbc -> jdbc.query("SELECT o.group_id, o.topic, count(*) AS lag"
                        + " FROM " + log + " m JOIN " + offsets + " o"
                        + " ON o.topic = m.topic AND o.log_partition = m.log_partition"
                        + " WHERE m.produced_at < ? AND m.log_offset > o.committed_offset"
                        + " GROUP BY o.group_id, o.topic",
                (rs, row) -> new Lag(rs.getString("group_id"), rs.getString("topic"), rs.getLong("lag")),
                Timestamp.from(cutoff)));
    }

    /**
     * Deletes records produced before the cutoff, in batches so no single transaction holds the table.
     *
     * @param cutoff records produced before this instant are deleted
     * @return how many were deleted
     */
    public long purgeBefore(Instant cutoff) {
        long total = 0;
        int deleted;
        do {
            deleted = inTransaction(jdbc -> jdbc.update("DELETE FROM " + log + " WHERE ctid IN (SELECT ctid FROM "
                    + log + " WHERE produced_at < ? LIMIT " + PURGE_BATCH + ")", Timestamp.from(cutoff)));
            total += deleted;
        } while (deleted == PURGE_BATCH);
        return total;
    }

    private Optional<Position> claim(JdbcTemplate jdbc, String groupId, Collection<String> topics) {
        // Least recently processed first: next_attempt_at is moved to now() on every success, so a busy
        // partition does not starve the others.
        List<Position> rows = jdbc.query("SELECT o.topic, o.log_partition, o.committed_offset, o.attempts"
                        + " FROM " + offsets + " o JOIN " + head + " h"
                        + " ON h.topic = o.topic AND h.log_partition = o.log_partition"
                        + " WHERE o.group_id = ? AND o.topic = ANY (?) AND o.next_attempt_at <= now()"
                        + " AND h.next_offset > o.committed_offset + 1"
                        + " ORDER BY o.next_attempt_at LIMIT 1 FOR UPDATE OF o SKIP LOCKED",
                (rs, row) -> new Position(rs.getString("topic"), rs.getInt("log_partition"),
                        rs.getLong("committed_offset"), rs.getInt("attempts")),
                groupId, topics.toArray(new String[0]));
        return rows.stream().findFirst();
    }

    private Optional<StoredRecord> next(JdbcTemplate jdbc, Position position) {
        return jdbc.query("SELECT log_offset, record_key, headers, payload, produced_at FROM " + log
                        + " WHERE topic = ? AND log_partition = ? AND log_offset > ?"
                        + " ORDER BY log_offset LIMIT 1",
                (rs, row) -> new StoredRecord(position.topic(), position.partition(), rs.getLong("log_offset"),
                        rs.getBytes("record_key"), decode(rs.getString("headers")), rs.getBytes("payload"),
                        rs.getTimestamp("produced_at").toInstant()),
                position.topic(), position.partition(), position.committedOffset()).stream().findFirst();
    }

    private void apply(JdbcTemplate jdbc, String groupId, Position position, StoredRecord record, Outcome outcome) {
        if (outcome instanceof Outcome.Retry retry) {
            jdbc.update("UPDATE " + offsets + " SET attempts = ?,"
                            + " next_attempt_at = now() + CAST(? AS interval), last_error = ?"
                            + " WHERE group_id = ? AND topic = ? AND log_partition = ?",
                    retry.failures(), retry.delay().toMillis() + " milliseconds", retry.errorType(),
                    groupId, position.topic(), position.partition());
            return;
        }
        jdbc.update("UPDATE " + offsets + " SET committed_offset = ?, attempts = 0, last_error = NULL,"
                        + " next_attempt_at = now() WHERE group_id = ? AND topic = ? AND log_partition = ?",
                record.offset(), groupId, position.topic(), position.partition());
    }

    private <T> T inTransaction(Function<JdbcTemplate, T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.apply(new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
                connection.commit();
                return result;
            // CHECKSTYLE.OFF: IllegalCatch - roll back on any failure, then rethrow it unchanged.
            } catch (RuntimeException e) {
            // CHECKSTYLE.ON: IllegalCatch
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new LogAccessException("Writing to the log failed", e);
        }
    }

    /**
     * Headers as a JSON list, so a repeated header survives and an operator can read the table. A value
     * that is valid UTF-8 is stored as text under {@code v}; anything else is stored as base64 under
     * {@code b}, because a header from an external producer may be arbitrary bytes.
     */
    private String encode(Headers headers) {
        List<Map<String, String>> list = new ArrayList<>();
        if (headers != null) {
            for (Header header : headers) {
                String text = utf8(header.value());
                list.add(text != null ? Map.of("k", header.key(), "v", text)
                        : Map.of("k", header.key(), "b", Base64.getEncoder().encodeToString(header.value())));
            }
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Header list could not be encoded", e);
        }
    }

    private Headers decode(String json) {
        RecordHeaders headers = new RecordHeaders();
        try {
            for (Map<String, String> entry : objectMapper.readValue(json, HEADER_LIST)) {
                String text = entry.get("v");
                headers.add(entry.get("k"), text != null ? text.getBytes(StandardCharsets.UTF_8)
                        : Base64.getDecoder().decode(entry.get("b")));
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Header list in the log could not be decoded", e);
        }
        return headers;
    }

    private static String utf8(byte[] value) {
        if (value == null) {
            return null;
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value)).toString();
            // NUL is valid UTF-8 but jsonb refuses it, and binary headers - a dead letter's original partition
            // and offset - are full of it.
            return text.indexOf('\0') >= 0 ? null : text;
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** Where an appended record landed. */
    public record Appended(String topic, int partition, long offset, Instant producedAt) {
    }

    /** A group's claimed position on one partition. */
    public record Position(String topic, int partition, long committedOffset, int attempts) {
    }

    /** One group's distance behind the heads of one topic. */
    public record Lag(String groupId, String topic, long records) {
    }

    /** Decides what happens to the next record of a claimed partition. */
    @FunctionalInterface
    public interface RecordHandler {

        /**
         * Handles the record.
         *
         * @param position the claimed position, carrying the record's failed attempts so far
         * @param record   the next record after the position
         * @return what to do with the position; throwing leaves it unchanged
         */
        Outcome handle(Position position, StoredRecord record);
    }
}
