package ru.ludwigandreas.messaging.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.messaging.api.DeadLetterTopics;

/** The one dead-letter naming convention, which a Checkstyle rule stops anyone re-declaring. */
class DeadLetterTopicsTest {

    @Test
    @DisplayName("the default suffix is appended to the source topic")
    void appendsTheDefaultSuffix() {
        assertThat(DeadLetterTopics.forTopic("orders", DeadLetterTopics.DEFAULT_SUFFIX))
                .isEqualTo("orders.dlt");
    }

    @Test
    @DisplayName("a blank or absent suffix falls back to the platform's, never to no suffix at all")
    void fallsBackToTheDefaultSuffix() {
        assertThat(DeadLetterTopics.forTopic("orders", null)).isEqualTo("orders.dlt");
        assertThat(DeadLetterTopics.forTopic("orders", "  ")).isEqualTo("orders.dlt");
    }

    @Test
    @DisplayName("a consumer may name its own destination")
    void honoursAConfiguredSuffix() {
        assertThat(DeadLetterTopics.forTopic("orders", "-failed")).isEqualTo("orders-failed");
    }

    @Test
    @DisplayName("a missing source topic fails loudly rather than producing a topic named '.dlt'")
    void refusesAMissingSourceTopic() {
        assertThatThrownBy(() -> DeadLetterTopics.forTopic("", ".dlt"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * {@code -1} is not an arbitrary constant. Preserving the source partition would require the
     * dead-letter topic to have at least as many partitions, which nothing enforces - and a send to a
     * partition that does not exist fails silently.
     */
    @Test
    @DisplayName("the dead-letter send lets the broker choose the partition")
    void letsTheBrokerChooseThePartition() {
        assertThat(DeadLetterTopics.BROKER_CHOOSES_PARTITION).isEqualTo(-1);
    }
}
