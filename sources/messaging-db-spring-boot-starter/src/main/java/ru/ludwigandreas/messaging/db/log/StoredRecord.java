package ru.ludwigandreas.messaging.db.log;

import java.time.Instant;
import org.apache.kafka.common.header.Headers;

/**
 * One record as the log holds it: the bytes the producer wrote, before any deserializer has seen them.
 *
 * @param topic      the topic
 * @param partition  the partition
 * @param offset     the offset within the partition
 * @param key        the serialized key, or {@code null}
 * @param headers    the headers, in the order they were written
 * @param payload    the serialized payload, or {@code null} for a tombstone
 * @param producedAt when the log accepted it; the record's timestamp, as a broker's log-append time is
 */
public record StoredRecord(String topic, int partition, long offset, byte[] key, Headers headers, byte[] payload,
                           Instant producedAt) {
}
