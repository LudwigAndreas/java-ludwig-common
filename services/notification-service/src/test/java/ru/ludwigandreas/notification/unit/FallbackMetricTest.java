package ru.ludwigandreas.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.notification.service.metrics.MicrometerNotificationMetrics;
import ru.ludwigandreas.notification.service.model.ChannelType;

/**
 * The fallback counter, and the tags it must never carry.
 *
 * <p>Two separate concerns, and the second is the one with teeth. The counter has to exist and be
 * distinguishable, because a deployment that enables the fallback for a broad category class needs to
 * see it fire on the first day rather than discover months later that campaigns have been filling
 * inboxes. And its tags have to stay bounded: this service's PII discipline is explicit that no metric
 * takes a recipient, an address, a template variable or a delivery id, because each is unbounded and
 * one tag valued by recipient turns a campaign to a million people into a million time series.
 */
class FallbackMetricTest {

    private static final String DELIVERIES = "notification.deliveries";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerNotificationMetrics metrics =
            new MicrometerNotificationMetrics(registry);

    @Test
    @DisplayName("a fallback delivery is counted with its own outcome tag")
    void fallbackIsCountedSeparately() {
        metrics.recordDeliveryFallback(ChannelType.IN_APP, "campaigns");

        assertThat(tagsOf()).contains(Tag.of("outcome", "fallback"));
        assertThat(tagsOf()).contains(Tag.of("channel", "IN_APP"));
        assertThat(tagsOf()).contains(Tag.of("category", "campaigns"));
    }

    /**
     * Distinguishable from an ordinary enqueue, which is the whole point of a separate outcome: a
     * fallback folded into {@code enqueued} would be invisible in exactly the dashboard somebody
     * would look at to find it.
     */
    @Test
    @DisplayName("a fallback is not counted as an ordinary enqueue")
    void fallbackIsNotAnEnqueue() {
        metrics.recordDeliveryFallback(ChannelType.IN_APP, "campaigns");

        assertThat(tagsOf()).doesNotContain(Tag.of("outcome", "enqueued"));
    }

    /**
     * The tag set is closed, and this asserts it by name rather than by count so that a future tag
     * has to be added here deliberately.
     */
    @Test
    @DisplayName("no tag is valued by recipient, item, delivery or address")
    void noUnboundedTags() {
        metrics.recordDeliveryFallback(ChannelType.IN_APP, "campaigns");

        assertThat(tagsOf()).extracting(Tag::getKey)
                .containsExactlyInAnyOrder("channel", "category", "outcome")
                .doesNotContain("recipient", "recipientUserId", "owner", "item", "itemId",
                        "delivery", "deliveryId", "address", "subject");
    }

    private List<Tag> tagsOf() {
        Meter meter = registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals(DELIVERIES))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no " + DELIVERIES + " meter was registered; the counter name has changed"));
        return meter.getId().getTags();
    }
}
