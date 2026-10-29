package ru.ludwigandreas.outbox.unit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.outbox.config.OutboxProperties;

/** The retention settings refuse periods that would delete too early or never stop. */
class OutboxPropertiesValidationTest {

    @Test
    @DisplayName("the defaults are valid")
    void defaults() {
        assertThatCode(() -> new OutboxProperties().getRetention().validate()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a published period under a day is refused, naming the property and why")
    void publishedBelowTheIdempotencyFloor() {
        OutboxProperties.Retention retention = new OutboxProperties().getRetention();
        retention.setPublished(Duration.ofHours(1));

        assertThatThrownBy(retention::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ludwig.outbox.retention.published")
                .hasMessageContaining("idempotency");
    }

    @Test
    @DisplayName("a zero or negative period is refused rather than read as keep nothing")
    void nonPositivePeriods() {
        OutboxProperties.Retention zeroTerminal = new OutboxProperties().getRetention();
        zeroTerminal.setTerminal(Duration.ZERO);
        OutboxProperties.Retention negativeInterval = new OutboxProperties().getRetention();
        negativeInterval.setInterval(Duration.ofMinutes(-5));
        OutboxProperties.Retention noBatch = new OutboxProperties().getRetention();
        noBatch.setBatchSize(0);

        assertThatThrownBy(zeroTerminal::validate).hasMessageContaining("ludwig.outbox.retention.terminal");
        assertThatThrownBy(negativeInterval::validate).hasMessageContaining("ludwig.outbox.retention.interval");
        assertThatThrownBy(noBatch::validate).hasMessageContaining("ludwig.outbox.retention.batch-size");
    }
}
