package ru.ludwigandreas.audit.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.audit.Actor;
import ru.ludwigandreas.audit.AuditCategories;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditOutcome;

/**
 * The envelope's invariants.
 *
 * <p>Every validation the nine records it replaced performed is kept rather than relaxed to the weakest of
 * them: {@code ExportAuditEvent} refused a null timestamp and everyone defensively copied their details
 * map, so an event with no position in time or with a caller-mutable attribute map has to stay impossible.
 */
class AuditEventTest {

    @Test
    @DisplayName("an event with no category or no action cannot be constructed")
    void requiresACategoryAndAnAction() {
        assertThatThrownBy(() -> AuditEvent.builder().action("x").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("category");
        assertThatThrownBy(() -> AuditEvent.builder().category("x").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action");
        assertThatThrownBy(() -> AuditEvent.builder().category("x").action("  ").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an id and a timestamp are assigned when the caller supplies none")
    void assignsAnIdAndATimestamp() {
        AuditEvent event = AuditEvent.builder().category("x").action("y").build();

        assertThat(event.id()).isNotNull();
        assertThat(event.occurredAt()).isNotNull();
        // The id is the idempotency key for shipping, so two events are never the same event.
        assertThat(AuditEvent.builder().category("x").action("y").build().id()).isNotEqualTo(event.id());
    }

    @Test
    @DisplayName("an unattributed event carries the system actor rather than null")
    void defaultsToTheSystemActor() {
        AuditEvent event = AuditEvent.builder().category("x").action("y").build();

        assertThat(event.actor()).isEqualTo(Actor.system());
        assertThat(event.outcome().status()).isEqualTo(AuditOutcome.Status.SUCCESS);
    }

    @Test
    @DisplayName("the attributes map is copied, so a caller cannot change an event after recording it")
    void copiesTheAttributes() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("a", "1");
        AuditEvent event = AuditEvent.builder().category("x").action("y").attributes(mutable).build();

        mutable.put("b", "2");

        assertThat(event.attributes()).containsOnlyKeys("a");
        assertThatThrownBy(() -> event.attributes().put("c", "3"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * {@code Map.copyOf} rejects a null value and the module records feeding this envelope are full of
     * genuinely optional facts - a run with no saved report id, a call that received no response.
     */
    @Test
    @DisplayName("a null attribute value is dropped rather than rejected")
    void dropsNullAttributeValues() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("present", "1");
        attributes.put("absent", null);

        AuditEvent event = AuditEvent.builder().category("x").action("y").attributes(attributes).build();

        assertThat(event.attributes()).containsExactlyEntriesOf(Map.of("present", "1"));
    }

    @Test
    @DisplayName("attribute order is preserved, so the same action reads the same way twice")
    void preservesAttributeOrder() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("z", 1);
        attributes.put("a", 2);
        attributes.put("m", 3);

        AuditEvent event = AuditEvent.builder().category("x").action("y").attributes(attributes).build();

        assertThat(event.attributes().keySet()).containsExactly("z", "a", "m");
    }

    @Test
    @DisplayName("with() adds an attribute and leaves the original alone")
    void withAddsAnAttribute() {
        AuditEvent event = AuditEvent.builder()
                .category(AuditCategories.EXPORT).action("run.succeeded")
                .occurredAt(Instant.parse("2026-01-02T03:04:05Z"))
                .attributes(Map.of("a", "1"))
                .build();

        AuditEvent extended = event.with("b", "2");

        assertThat(event.attributes()).containsOnlyKeys("a");
        assertThat(extended.attributes()).containsOnlyKeys("a", "b");
        assertThat(extended.id()).isEqualTo(event.id());
        assertThat(extended.occurredAt()).isEqualTo(event.occurredAt());
    }

    @Test
    @DisplayName("with(null) is a no-op, so a caller need not guard an optional fact")
    void withIgnoresANullValue() {
        AuditEvent event = AuditEvent.builder().category("x").action("y").build();

        assertThat(event.with("k", null)).isSameAs(event);
    }

    @Test
    @DisplayName("an actor acting for someone else records both")
    void recordsActingOnBehalfOf() {
        Actor admin = Actor.of("admin-1", "USER").onBehalfOf("alice");

        assertThat(admin.subject()).isEqualTo("admin-1");
        assertThat(admin.onBehalfOf()).isEqualTo("alice");
    }
}
