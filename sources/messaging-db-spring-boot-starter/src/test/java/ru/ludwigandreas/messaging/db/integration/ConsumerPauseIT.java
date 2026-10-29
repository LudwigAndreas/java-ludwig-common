package ru.ludwigandreas.messaging.db.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import ru.ludwigandreas.messaging.db.log.LogStore;
import ru.ludwigandreas.messaging.db.log.LogStore.MoveTarget;
import ru.ludwigandreas.messaging.db.log.LogStore.Moved;
import ru.ludwigandreas.messaging.db.log.Outcome;
import ru.ludwigandreas.testsupport.container.Containers;

/**
 * A pause is a row every consumer's claim reads, and a move of a paused group's position takes the same row lock a
 * delivery holds - so both hold for every replica with nothing broadcast. Driven through the store directly, as each
 * replica's workers do, so "three replicas" is three callers of the same claim.
 */
class ConsumerPauseIT {

    private static final String SCHEMA = "pause_it";

    private static PGSimpleDataSource dataSource;
    private LogStore store;

    @BeforeAll
    static void installSchema() throws Exception {
        PostgreSQLContainer<?> postgres = Containers.postgres();
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        LogStoreIT.migrate(dataSource, SCHEMA, "pause_it_changelog");
    }

    @BeforeEach
    void emptyTheLog() {
        new JdbcTemplate(dataSource).execute("TRUNCATE " + SCHEMA + ".message_log, " + SCHEMA + ".partition_head, "
                + SCHEMA + ".consumer_offset, " + SCHEMA + ".consumer_pause");
        // One partition, so offsets are simply 0, 1, 2 - and a position is "last handled", -1 before the first record -
        // which keeps a move's arithmetic readable off the test.
        store = new LogStore(dataSource, new ObjectMapper(), SCHEMA, 1);
    }

    private void append(String topic, String value) {
        store.append(topic, "k".getBytes(StandardCharsets.UTF_8), new RecordHeaders(),
                value.getBytes(StandardCharsets.UTF_8));
    }

    /** Drains what three replicas' workers would claim for a group, returning the values delivered in order. */
    private List<String> drain(String group, List<String> topics) {
        List<String> delivered = new ArrayList<>();
        boolean worked;
        do {
            worked = false;
            for (int replica = 0; replica < 3; replica++) {
                worked |= store.processNext(group, topics, (position, record) -> {
                    delivered.add(record.topic() + ":" + new String(record.payload(), StandardCharsets.UTF_8));
                    return new Outcome.Advance();
                });
            }
        } while (worked);
        return delivered;
    }

    @Test
    @DisplayName("a paused topic is delivered by no replica, while other topics and groups carry on, until resumed")
    void pauseHoldsOnEveryReplica() {
        store.ensurePositions("billing", List.of("orders", "refunds"), true);
        store.ensurePositions("search", List.of("orders"), true);
        append("orders", "o1");
        append("refunds", "r1");

        assertThat(store.pause("billing", "orders", "olga", "poison record")).isTrue();
        assertThat(store.pause("billing", "orders", "olga", "again")).isFalse();

        assertThat(drain("billing", List.of("orders", "refunds"))).containsExactly("refunds:r1");
        assertThat(drain("search", List.of("orders"))).containsExactly("orders:o1");
        assertThat(store.pauses()).singleElement().satisfies(pause -> {
            assertThat(pause.pausedBy()).isEqualTo("olga");
            assertThat(pause.reason()).isEqualTo("poison record");
        });

        assertThat(store.resume("billing", "orders")).isTrue();
        assertThat(drain("billing", List.of("orders", "refunds"))).containsExactly("orders:o1");
        assertThat(store.resume("billing", "orders")).isFalse();
    }

    @Test
    @DisplayName("a move is refused unless the pair is paused, and changes nothing then")
    void moveNeedsAPause() {
        store.ensurePositions("billing", List.of("orders"), true);
        append("orders", "o1");

        assertThat(store.movePosition("billing", "orders", null, new MoveTarget(MoveTarget.Kind.LATEST, 0, null)))
                .isEmpty();
        assertThat(drain("billing", List.of("orders"))).containsExactly("orders:o1");
    }

    @Test
    @DisplayName("moving past a poison record skips it; moving back to a timestamp replays from there")
    void skipAndReplay() {
        store.ensurePositions("billing", List.of("orders"), true);
        append("orders", "o1");
        append("orders", "poison");
        append("orders", "o3");
        store.pause("billing", "orders", "olga", null);
        // The group got as far as the first record (offset 0), then kept failing on the poison one (offset 1).
        assertThat(store.movePosition("billing", "orders", 0, new MoveTarget(MoveTarget.Kind.OFFSET, 1, null)))
                .isPresent();

        Optional<List<Moved>> skipped = store.movePosition("billing", "orders", 0,
                new MoveTarget(MoveTarget.Kind.OFFSET, 2, null));

        assertThat(skipped).get().asList().singleElement().satisfies(moved -> {
            assertThat(((Moved) moved).from()).isEqualTo(0);
            assertThat(((Moved) moved).to()).isEqualTo(1);
            assertThat(((Moved) moved).skipped()).isEqualTo(1);
        });
        store.resume("billing", "orders");
        assertThat(drain("billing", List.of("orders"))).containsExactly("orders:o3");

        store.pause("billing", "orders", "olga", null);
        Optional<List<Moved>> replayed = store.movePosition("billing", "orders", null,
                new MoveTarget(MoveTarget.Kind.TIMESTAMP, 0, Instant.EPOCH));
        assertThat(replayed).get().asList().singleElement()
                .satisfies(moved -> assertThat(((Moved) moved).replayed()).isEqualTo(3));
        store.resume("billing", "orders");
        assertThat(drain("billing", List.of("orders"))).containsExactly("orders:o1", "orders:poison", "orders:o3");
    }

    @Test
    @DisplayName("an explicit offset outside the retained log is clamped to it")
    void clampedToTheLog() {
        store.ensurePositions("billing", List.of("orders"), true);
        append("orders", "o1");
        append("orders", "o2");
        store.pause("billing", "orders", "olga", null);

        Moved beyondHead = store.movePosition("billing", "orders", null,
                new MoveTarget(MoveTarget.Kind.OFFSET, 99, null)).orElseThrow().get(0);
        Moved beforeStart = store.movePosition("billing", "orders", null,
                new MoveTarget(MoveTarget.Kind.OFFSET, -5, null)).orElseThrow().get(0);

        assertThat(beyondHead.to()).isEqualTo(1);
        assertThat(beforeStart.to()).isEqualTo(-1);
    }
}
