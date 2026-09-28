package ru.ludwigandreas.ingest.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import ru.ludwigandreas.ingest.api.ParsedRecord;
import ru.ludwigandreas.ingest.engine.BatchAccumulator;

/**
 * The batch bounds, which are what actually hold the heap.
 *
 * <p>Both limits are tested independently, because the record-count bound is the one everybody thinks
 * of and the byte bound is the one that matters: a module bounded only by record count works until a
 * partner sends one row with a 200 MB free-text column.
 */
class BatchAccumulatorTest {

    private static ParsedRecord<String> record(long ordinal, long start, long size) {
        return ParsedRecord.parsed("r" + ordinal, ordinal, start, start + size, "raw");
    }

    @Test
    void flushesWhenTheRecordCountIsReached() {
        BatchAccumulator<String> batch = new BatchAccumulator<>(3, 1_000_000);

        for (int i = 0; i < 3; i++) {
            assertThat(batch.shouldFlushBefore(record(i, i * 10L, 10))).isFalse();
            batch.add(record(i, i * 10L, 10));
        }

        assertThat(batch.size()).isEqualTo(3);
        assertThat(batch.shouldFlushBefore(record(3, 30, 10))).isTrue();
    }

    @Test
    void flushesWhenTheByteBoundIsReachedFirst() {
        BatchAccumulator<String> batch = new BatchAccumulator<>(1_000_000, 100);

        batch.add(record(0, 0, 60));
        assertThat(batch.bytes()).isEqualTo(60);

        // 60 + 50 would be 110, past the bound, so the batch flushes first even though the record
        // count is nowhere near its limit.
        assertThat(batch.shouldFlushBefore(record(1, 60, 50))).isTrue();
    }

    @Test
    void neverExceedsTheBoundEvenMomentarily() {
        BatchAccumulator<String> batch = new BatchAccumulator<>(1_000_000, 100);
        batch.add(record(0, 0, 60));

        // Checked before adding rather than after: checking afterwards would let the peak exceed the
        // limit by one record, and for the record this bound exists to protect against that is the
        // entire problem.
        assertThat(batch.shouldFlushBefore(record(1, 60, 50))).isTrue();
        assertThat(batch.bytes()).isEqualTo(60);
    }

    @Test
    void identifiesARecordTooLargeForAnyBatchAsAPoisonRecord() {
        BatchAccumulator<String> batch = new BatchAccumulator<>(1_000, 100);

        assertThat(batch.wouldOverflowAlone(record(0, 0, 101))).isTrue();
        assertThat(batch.wouldOverflowAlone(record(0, 0, 100))).isFalse();
    }

    @Test
    void reportsTheEndOffsetOfTheLastRecordRatherThanTheSumOfSizes() {
        BatchAccumulator<String> batch = new BatchAccumulator<>(10, 1_000);
        // Records separated by a one-byte delimiter: the sum of sizes is 20, the end offset is 21.
        batch.add(record(0, 0, 10));
        batch.add(record(1, 11, 10));

        assertThat(batch.endOffset()).isEqualTo(21);
        assertThat(batch.lastOrdinal()).isEqualTo(1);
    }

    @Test
    void doesNotFlushAnEmptyBatch() {
        BatchAccumulator<String> batch = new BatchAccumulator<>(1, 1);

        assertThat(batch.isEmpty()).isTrue();
        assertThat(batch.shouldFlushBefore(record(0, 0, 9999))).isFalse();
    }

    @Test
    void clearingResetsBothBounds() {
        BatchAccumulator<String> batch = new BatchAccumulator<>(10, 1_000);
        batch.add(record(0, 0, 100));

        batch.clear();

        assertThat(batch.isEmpty()).isTrue();
        assertThat(batch.bytes()).isZero();
    }

    @Test
    void refusesBoundsThatCouldNeverHoldARecord() {
        assertThatThrownBy(() -> new BatchAccumulator<String>(0, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BatchAccumulator<String>(10, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
