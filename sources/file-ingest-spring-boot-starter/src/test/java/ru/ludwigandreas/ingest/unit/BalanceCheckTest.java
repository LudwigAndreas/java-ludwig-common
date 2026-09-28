package ru.ludwigandreas.ingest.unit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.ingest.api.IngestRunSummary;
import ru.ludwigandreas.ingest.engine.BalanceCheck;
import ru.ludwigandreas.ingest.exception.BalanceFailedException;

/**
 * The correctness proof.
 *
 * <p>These are the assertions that make "this module does not lose records" a checkable statement
 * rather than a claim, so they are tested for what they reject as carefully as for what they accept.
 */
class BalanceCheckTest {

    private static IngestRunSummary summary(long read, long applied, long quarantined, long skipped) {
        return new IngestRunSummary(UUID.randomUUID(), "partner-catalogue", "s3://drop/c.csv", "etag",
                1024, read, applied, quarantined, skipped, Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    void acceptsARunWhereEveryRecordIsAccountedFor() {
        assertThatCode(() -> BalanceCheck.verify(summary(1000, 950, 40, 10), null))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsARunWithRecordsThatWentNowhere() {
        // 950 + 40 + 5 == 995, not 1000. Five records were read and are in none of the three places
        // a record can be, which is the data loss this check exists to make impossible.
        assertThatThrownBy(() -> BalanceCheck.verify(summary(1000, 950, 40, 5), null))
                .isInstanceOf(BalanceFailedException.class)
                .hasMessageContaining("read=1000")
                .hasMessageContaining("applied=950")
                .hasMessageContaining("quarantined=40")
                .hasMessageContaining("skipped=5");
    }

    @Test
    void rejectsARunThatAccountedForMoreRecordsThanItRead() {
        // The other direction, which is a batch counted twice rather than a record lost, and is
        // equally a reason not to complete: the target would have rows the file did not contain.
        assertThatThrownBy(() -> BalanceCheck.verify(summary(1000, 1010, 0, 0), null))
                .isInstanceOf(BalanceFailedException.class);
    }

    @Test
    void rejectsARunThatReadFewerRecordsThanItsSentinelDeclared() {
        // Internally consistent and short: this is what a truncated upload that happened to end on a
        // record boundary looks like, and the sentinel's count is the only thing in the module that
        // can detect it, because it is the only number that comes from outside the run.
        assertThatThrownBy(() -> BalanceCheck.verify(summary(999, 999, 0, 0), 1000L))
                .isInstanceOf(BalanceFailedException.class)
                .hasMessageContaining("sentinel declared 1000");
    }

    @Test
    void acceptsARunThatMatchesItsSentinel() {
        assertThatCode(() -> BalanceCheck.verify(summary(1000, 1000, 0, 0), 1000L))
                .doesNotThrowAnyException();
    }

    @Test
    void comparesWhatIsActuallyStagedAgainstWhatTheRunBelievesItWrote() {
        // The engine's own count reconciles with itself in every case; only counting the table
        // catches a staging writer that reported five thousand and wrote four thousand nine hundred.
        assertThatThrownBy(() -> BalanceCheck.verifyStaged(summary(1000, 1000, 0, 0), 900))
                .isInstanceOf(BalanceFailedException.class);
        assertThatCode(() -> BalanceCheck.verifyStaged(summary(1000, 1000, 0, 0), 1000))
                .doesNotThrowAnyException();
    }

    @Test
    void skipsTheStagedComparisonWhenTheApplierCollapsedDuplicates() {
        // A run with skipped records has deliberately staged fewer rows than it applied, and the
        // check has no way to know how many fewer. Stated rather than guessed: a check that reported
        // failures on correct runs would be switched off, and then it would catch nothing at all.
        assertThatCode(() -> BalanceCheck.verifyStaged(summary(1000, 900, 0, 100), 900))
                .doesNotThrowAnyException();
    }
}
