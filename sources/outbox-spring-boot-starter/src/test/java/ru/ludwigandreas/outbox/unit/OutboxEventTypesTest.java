package ru.ludwigandreas.outbox.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.outbox.api.OutboxEventTypes;

class OutboxEventTypesTest {

    @Test
    void declarationsFromSeveralBeansAreMerged() {
        OutboxEventTypes merged = OutboxEventTypes.merge(List.of(
                OutboxEventTypes.of(Map.of("A", String.class)),
                OutboxEventTypes.of(Map.of("B", Integer.class, "A", String.class))));

        assertThat(merged.payloadOf("A")).contains(String.class);
        assertThat(merged.payloadOf("B")).contains(Integer.class);
        assertThat(merged.payloadOf("C")).isEmpty();
    }

    @Test
    void oneTypeDeclaredWithTwoClassesIsRefused() {
        assertThatThrownBy(() -> OutboxEventTypes.merge(List.of(
                OutboxEventTypes.of(Map.of("A", String.class)),
                OutboxEventTypes.of(Map.of("A", Integer.class)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'A'").hasMessageContaining("java.lang.Integer");
    }

    @Test
    void aRoutedDeclarationCarriesItsRouteAndConflictsWithAnUnroutedOne() {
        OutboxEventTypes routed = OutboxEventTypes.routed("Notify", String.class, "notifications");

        assertThat(routed.declarations().get("Notify").route()).isEqualTo("notifications");
        assertThatThrownBy(() -> OutboxEventTypes.merge(List.of(routed,
                OutboxEventTypes.of(Map.of("Notify", String.class)))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Notify");
    }
}
