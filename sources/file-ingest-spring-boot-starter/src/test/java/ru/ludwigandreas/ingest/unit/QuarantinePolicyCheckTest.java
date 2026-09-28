package ru.ludwigandreas.ingest.unit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import ru.ludwigandreas.ingest.config.FileIngestProperties;
import ru.ludwigandreas.ingest.engine.QuarantinePolicyCheck;
import ru.ludwigandreas.ingest.exception.QuarantineThresholdExceededException;

/**
 * Threshold behaviour at and past the ratio, which is where the policy earns its keep.
 */
class QuarantinePolicyCheckTest {

    private static FileIngestProperties.Quarantine threshold(double ratio) {
        FileIngestProperties.Quarantine quarantine = new FileIngestProperties.Quarantine();
        quarantine.setPolicy(FileIngestProperties.QuarantinePolicy.THRESHOLD);
        quarantine.setMaxRatio(ratio);
        return quarantine;
    }

    @Test
    void allowsARateUnderTheLimit() {
        // 4 in 1000 is 0.004, under 0.005.
        assertThatCode(() -> QuarantinePolicyCheck.verify("t", 1000, 4, 100, threshold(0.005)))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsARateExactlyAtTheLimit() {
        // 5 in 1000 is 0.005, which is the limit and not past it. Tested because "at the threshold"
        // is the boundary an operator sets the number to, and failing there would make the configured
        // value mean something one record different from what it says.
        assertThatCode(() -> QuarantinePolicyCheck.verify("t", 1000, 5, 100, threshold(0.005)))
                .doesNotThrowAnyException();
    }

    @Test
    void failsPastTheLimit() {
        assertThatThrownBy(() -> QuarantinePolicyCheck.verify("t", 1000, 6, 100, threshold(0.005)))
                .isInstanceOf(QuarantineThresholdExceededException.class)
                .hasMessageContaining("quarantined 6 of 1000");
    }

    @Test
    void doesNotApplyTheRatioBeforeItIsMeaningful() {
        // The very first record being bad gives a ratio of 1.0, which is past every threshold. Failing
        // there would be fail-fast wearing the wrong name, and would make the threshold policy
        // indistinguishable from fail-fast for any file whose first record is malformed.
        assertThatCode(() -> QuarantinePolicyCheck.verify("t", 1, 1, 100, threshold(0.005)))
                .doesNotThrowAnyException();
        assertThatCode(() -> QuarantinePolicyCheck.verify("t", 99, 99, 100, threshold(0.005)))
                .doesNotThrowAnyException();
    }

    @Test
    void failFastStopsOnTheFirstBadRecord() {
        FileIngestProperties.Quarantine quarantine = new FileIngestProperties.Quarantine();
        quarantine.setPolicy(FileIngestProperties.QuarantinePolicy.FAIL_FAST);

        assertThatThrownBy(() -> QuarantinePolicyCheck.verify("t", 1, 1, 100, quarantine))
                .isInstanceOf(QuarantineThresholdExceededException.class);
    }

    @Test
    void neitherPolicyFiresWhenNothingWasQuarantined() {
        FileIngestProperties.Quarantine failFast = new FileIngestProperties.Quarantine();
        failFast.setPolicy(FileIngestProperties.QuarantinePolicy.FAIL_FAST);

        assertThatCode(() -> QuarantinePolicyCheck.verify("t", 4_000_000, 0, 100, failFast))
                .doesNotThrowAnyException();
        assertThatCode(() -> QuarantinePolicyCheck.verify("t", 4_000_000, 0, 100, threshold(0.0)))
                .doesNotThrowAnyException();
    }
}
