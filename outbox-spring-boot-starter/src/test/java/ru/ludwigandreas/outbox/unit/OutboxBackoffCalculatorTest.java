package ru.ludwigandreas.outbox.unit;

import org.junit.jupiter.api.Test;
import ru.ludwigandreas.outbox.backoff.OutboxBackoffCalculator;
import ru.ludwigandreas.outbox.config.OutboxProperties;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxBackoffCalculatorTest {

    private OutboxProperties.Retry retry() {
        OutboxProperties.Retry retry = new OutboxProperties.Retry();
        retry.setInitialInterval(Duration.ofSeconds(1));
        retry.setMultiplier(2.0);
        retry.setMaxInterval(Duration.ofSeconds(10));
        return retry;
    }

    @Test
    void firstAttemptUsesInitialInterval() {
        OutboxBackoffCalculator calculator = new OutboxBackoffCalculator(retry());

        assertThat(calculator.nextDelay(1)).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void growsExponentiallyWithAttempts() {
        OutboxBackoffCalculator calculator = new OutboxBackoffCalculator(retry());

        assertThat(calculator.nextDelay(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(calculator.nextDelay(3)).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void capsAtMaxInterval() {
        OutboxBackoffCalculator calculator = new OutboxBackoffCalculator(retry());

        assertThat(calculator.nextDelay(10)).isEqualTo(Duration.ofSeconds(10));
    }
}
