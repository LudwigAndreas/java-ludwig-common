package ru.ludwigandreas.audit.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import ru.ludwigandreas.audit.Actor;
import ru.ludwigandreas.audit.AuditCategories;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditOutcome;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.audit.CompositeAuditSink;
import ru.ludwigandreas.audit.NoopAuditSink;
import ru.ludwigandreas.audit.Resource;
import ru.ludwigandreas.audit.Slf4jAuditSink;

/**
 * The contract every {@link AuditSink} implementation shares, run against all of them, so that the
 * implementations cannot drift.
 *
 * <p>Deliberately not a test of what each sink writes - a logger writes a line, the JPA sink writes a row,
 * the outbox sink writes an outbox message, and asserting on those belongs with each. What is shared is
 * narrower and easier to break by accident: a sink must accept every legal envelope, including the sparse
 * ones, and must not reject an event because an optional component is absent. Seven of the nine SPIs this
 * replaced dereferenced a component that was nullable in their own record.
 *
 * <p>The database-backed and outbox-backed sinks live in {@code audit-spring-boot-starter} and cannot be
 * constructed here without a data source, so they run the same cases from
 * {@code AuditSinkConformanceIT} there against real infrastructure. This class covers the sinks that need
 * nothing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditSinkConformanceTest {

    /** Every sink that can be constructed without infrastructure. */
    static Stream<AuditSink> sinks() {
        return Stream.of(
                new Slf4jAuditSink(),
                NoopAuditSink.INSTANCE,
                CompositeAuditSink.of(new Slf4jAuditSink(), NoopAuditSink.INSTANCE),
                CompositeAuditSink.of());
    }

    /** Every envelope shape a module in this platform actually produces, sparse ones included. */
    static Stream<AuditEvent> events() {
        return Stream.of(fullEvent(), minimalEvent(), deniedEvent(), partialEvent());
    }

    @ParameterizedTest
    @MethodSource("sinks")
    @DisplayName("every sink accepts every envelope shape, including the sparse ones")
    void acceptsEveryEnvelopeShape(AuditSink sink) {
        events().forEach(event -> assertThatCode(() -> sink.record(event)).doesNotThrowAnyException());
    }

    @ParameterizedTest
    @MethodSource("sinks")
    @DisplayName("no sink mutates the event it is given")
    void doesNotMutateTheEvent(AuditSink sink) {
        AuditEvent event = fullEvent();
        AuditEvent before = event.toBuilder().build();

        sink.record(event);

        assertThat(event.attributes()).isEqualTo(before.attributes());
        assertThat(event.id()).isEqualTo(before.id());
        assertThat(event.occurredAt()).isEqualTo(before.occurredAt());
    }

    /**
     * The composite's contract, which the wiring depends on: an outage in one sink must not decide whether
     * the others were given the event, because the order of a configuration list would otherwise silently
     * become a dependency.
     */
    @Test
    @DisplayName("a composite attempts every delegate even when an earlier one throws")
    void aCompositeAttemptsEveryDelegate() {
        List<String> reached = new ArrayList<>();
        AuditSink failing = event -> {
            reached.add("failing");
            throw new IllegalStateException("sink down");
        };
        AuditSink working = event -> reached.add("working");

        assertThatThrownBy(() -> CompositeAuditSink.of(failing, working).record(fullEvent()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(reached).containsExactly("failing", "working");
    }

    /**
     * And it rethrows, which is what lets the failure policy in front of it still fail a state mutation
     * whose row did not reach the database.
     */
    @Test
    @DisplayName("a composite rethrows the first failure, with the rest suppressed")
    void aCompositeRethrowsTheFirstFailure() {
        AuditSink first = event -> {
            throw new IllegalStateException("first");
        };
        AuditSink second = event -> {
            throw new IllegalArgumentException("second");
        };

        assertThatThrownBy(() -> CompositeAuditSink.of(first, second).record(fullEvent()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("first")
                .satisfies(thrown -> assertThat(thrown.getSuppressed())
                        .singleElement()
                        .isInstanceOf(IllegalArgumentException.class));
    }

    @Test
    @DisplayName("a composite drops nulls rather than failing on them, so a conditional bean needs no guard")
    void aCompositeDropsNulls() {
        CompositeAuditSink composite = new CompositeAuditSink(java.util.Arrays.asList(null, new Slf4jAuditSink()));

        assertThat(composite.delegates()).hasSize(1);
        assertThatCode(() -> composite.record(fullEvent())).doesNotThrowAnyException();
    }

    private static AuditEvent fullEvent() {
        return AuditEvent.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .occurredAt(Instant.parse("2026-01-02T03:04:05Z"))
                .category(AuditCategories.SETTINGS)
                .action("setting.set")
                .actor(new Actor("alice", "USER", "Alice", "bob"))
                .resource(new Resource("setting", "ui.page-size", "Page size"))
                .outcome(AuditOutcome.success())
                .correlationId("corr-1")
                .traceId("trace-1")
                .attributes(Map.of("oldValue", "50", "newValue", "75"))
                .build();
    }

    /** Category and action only - the least an envelope can legally carry. */
    private static AuditEvent minimalEvent() {
        return AuditEvent.builder().category(AuditCategories.ACCESS).action("access.denied").build();
    }

    private static AuditEvent deniedEvent() {
        return AuditEvent.builder()
                .category(AuditCategories.ACCESS)
                .action("access.denied")
                .actor(Actor.of("alice"))
                .outcome(AuditOutcome.denied("out-of-scope"))
                .build();
    }

    private static AuditEvent partialEvent() {
        return AuditEvent.builder()
                .category(AuditCategories.EXPORT)
                .action("run.succeeded")
                .resource(Resource.ofType("report-run"))
                .outcome(AuditOutcome.partial("degraded stages: pricing"))
                .build();
    }
}
