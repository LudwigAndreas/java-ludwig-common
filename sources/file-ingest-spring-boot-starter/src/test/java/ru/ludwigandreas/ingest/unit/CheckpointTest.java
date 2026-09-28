package ru.ludwigandreas.ingest.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import ru.ludwigandreas.ingest.api.Checkpoint;
import ru.ludwigandreas.ingest.api.CheckpointKind;

/**
 * Checkpoint arithmetic for both sealed variants.
 *
 * <p>An off-by-one here duplicates or drops exactly one record per resume, which is the single most
 * likely way this module could lose data - and the symptom looks like a parser bug, nowhere near the
 * code responsible. The offsets are therefore pinned here rather than exercised incidentally.
 */
class CheckpointTest {

    @Test
    void aByteOffsetCheckpointAdvancesToTheEndOfTheLastCommittedRecord() {
        Checkpoint.ByteOffset start = new Checkpoint.ByteOffset(0, 0);

        Checkpoint.ByteOffset after = start.advancedTo(1024, 50);

        // The offset is the first byte NOT yet committed, so it is also exactly the number of bytes
        // committed - which is what makes open(uri, ByteRange.from(offset)) correct with no
        // adjustment at the call site.
        assertThat(after.offset()).isEqualTo(1024);
        assertThat(after.position()).isEqualTo(1024);
        assertThat(after.recordsCommitted()).isEqualTo(50);
        assertThat(after.kind()).isEqualTo(CheckpointKind.BYTE_OFFSET);
    }

    @Test
    void aRecordOrdinalCheckpointAdvancesByTheBatchSize() {
        Checkpoint.RecordOrdinal after = new Checkpoint.RecordOrdinal(120).advancedBy(30);

        assertThat(after.ordinal()).isEqualTo(150);
        // Position and record count are the same number for this shape: the ordinal is both where to
        // resume and how many records to skip.
        assertThat(after.position()).isEqualTo(150);
        assertThat(after.recordsCommitted()).isEqualTo(150);
        assertThat(after.kind()).isEqualTo(CheckpointKind.RECORD_ORDINAL);
    }

    @Test
    void startsAtZeroInEitherShape() {
        assertThat(Checkpoint.start(CheckpointKind.BYTE_OFFSET))
                .isEqualTo(new Checkpoint.ByteOffset(0, 0));
        assertThat(Checkpoint.start(CheckpointKind.RECORD_ORDINAL))
                .isEqualTo(new Checkpoint.RecordOrdinal(0));
    }

    @Test
    void rebuildsFromWhatWasStoredOnTheRunRow() {
        Checkpoint byteOffset = Checkpoint.of(CheckpointKind.BYTE_OFFSET, 800_000_000L, 4_000_000L);
        Checkpoint ordinal = Checkpoint.of(CheckpointKind.RECORD_ORDINAL, 4_000_000L, 4_000_000L);

        assertThat(byteOffset).isEqualTo(new Checkpoint.ByteOffset(800_000_000L, 4_000_000L));
        assertThat(ordinal).isEqualTo(new Checkpoint.RecordOrdinal(4_000_000L));
    }

    @Test
    void refusesANegativePosition() {
        assertThatThrownBy(() -> new Checkpoint.ByteOffset(-1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Checkpoint.RecordOrdinal(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
