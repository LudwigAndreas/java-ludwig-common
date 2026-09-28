package ru.ludwigandreas.messaging.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ContainerProperties;
import ru.ludwigandreas.messaging.settings.ConsumerSettings;
import ru.ludwigandreas.messaging.settings.MessagingProperties;
import ru.ludwigandreas.messaging.settings.ResolvedConsumerSettings;

/**
 * Three-level resolution, which is the reason every field on {@link ConsumerSettings} is nullable.
 *
 * <p>The failure this guards against is specific: with primitive fields, a per-consumer block that
 * overrode one setting would silently reset every other one to zero or false - so a consumer asking for
 * six attempts would get a dead-letter suffix of null and a backoff of zero.
 */
class MessagingPropertiesTest {

    @Test
    @DisplayName("a consumer that configures nothing gets the platform defaults")
    void fallsBackToThePlatformDefaults() {
        ResolvedConsumerSettings resolved = new MessagingProperties().resolve("anything");

        assertThat(resolved.ackMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
        assertThat(resolved.ordered()).isTrue();
        assertThat(resolved.dedup()).isTrue();
        assertThat(resolved.nonBlocking()).isFalse();
        assertThat(resolved.deadLetterEnabled()).isTrue();
        assertThat(resolved.deadLetterSuffix()).isEqualTo(".dlt");
        assertThat(resolved.initialBackoff()).isEqualTo(Duration.ofSeconds(1));
        assertThat(resolved.multiplier()).isEqualTo(3.0);
        assertThat(resolved.maxBackoff()).isEqualTo(Duration.ofSeconds(30));
        assertThat(resolved.maxAttempts()).isEqualTo(4);
        assertThat(resolved.silenceThreshold()).isNull();
        assertThat(resolved.gatesEventVersion()).isFalse();
    }

    @Test
    @DisplayName("the defaults block overrides the platform constants for every consumer")
    void appliesTheDefaultsBlock() {
        MessagingProperties properties = new MessagingProperties();
        properties.getDefaults().setAckMode(ContainerProperties.AckMode.MANUAL);
        properties.getDefaults().getRetry().setMaxAttempts(7);

        ResolvedConsumerSettings resolved = properties.resolve("anything");

        assertThat(resolved.ackMode()).isEqualTo(ContainerProperties.AckMode.MANUAL);
        assertThat(resolved.maxAttempts()).isEqualTo(7);
    }

    /**
     * The test the nullable fields exist for. One overridden setting must leave the other thirteen alone.
     */
    @Test
    @DisplayName("a per-consumer override changes only what it names")
    void overridesOnlyWhatItNames() {
        MessagingProperties properties = new MessagingProperties();
        properties.getDefaults().getRetry().setMaxAttempts(7);
        ConsumerSettings own = new ConsumerSettings();
        own.getRetry().setMaxBackoff(Duration.ofMinutes(5));
        properties.getConsumers().put("projection", own);

        ResolvedConsumerSettings resolved = properties.resolve("projection");

        assertThat(resolved.maxBackoff()).isEqualTo(Duration.ofMinutes(5));
        assertThat(resolved.maxAttempts()).as("inherited from the defaults block").isEqualTo(7);
        assertThat(resolved.ackMode()).as("inherited from the platform constant")
                .isEqualTo(ContainerProperties.AckMode.RECORD);
        assertThat(resolved.deadLetterSuffix()).isEqualTo(".dlt");
    }

    @Test
    @DisplayName("a version bound is what installs the gate at all")
    void reportsWhetherItGatesVersions() {
        MessagingProperties properties = new MessagingProperties();
        ConsumerSettings own = new ConsumerSettings();
        own.getAcceptedVersions().setMax(2);
        properties.getConsumers().put("projection", own);

        assertThat(properties.resolve("projection").gatesEventVersion()).isTrue();
        assertThat(properties.resolve("projection").minEventVersion()).isEqualTo(1);
        assertThat(properties.resolve("projection").maxEventVersion()).isEqualTo(2);
        assertThat(properties.resolve("other").gatesEventVersion()).isFalse();
    }

    @Test
    @DisplayName("an attempt budget below one is refused: it counts the first delivery")
    void refusesAnImpossibleAttemptBudget() {
        MessagingProperties properties = new MessagingProperties();
        properties.getDefaults().getRetry().setMaxAttempts(0);

        assertThatThrownBy(() -> properties.resolve("anything"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1");
    }

    @Test
    @DisplayName("an inverted accepted-version range is refused")
    void refusesAnInvertedVersionRange() {
        MessagingProperties properties = new MessagingProperties();
        properties.getDefaults().getAcceptedVersions().setMin(5);
        properties.getDefaults().getAcceptedVersions().setMax(2);

        assertThatThrownBy(() -> properties.resolve("anything"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5..2");
    }
}
