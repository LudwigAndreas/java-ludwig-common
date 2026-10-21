package ru.ludwigandreas.messaging.db.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import liquibase.integration.spring.SpringLiquibase;
import org.apache.kafka.clients.producer.internals.BuiltInPartitioner;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import ru.ludwigandreas.messaging.db.config.MessagingDbLiquibaseAutoConfiguration;
import ru.ludwigandreas.messaging.db.log.LogStore;
import ru.ludwigandreas.messaging.db.log.Outcome;
import ru.ludwigandreas.messaging.db.log.StoredRecord;
import ru.ludwigandreas.testsupport.container.Containers;

/** The log's own guarantees, against a real PostgreSQL and without Spring: offsets, claims, positions, purge. */
class LogStoreIT {

    private static final String SCHEMA = "store_it";
    private static final int PARTITIONS = 4;

    private static PGSimpleDataSource dataSource;
    private static JdbcTemplate jdbc;
    private LogStore store;

    @BeforeAll
    static void installSchema() throws Exception {
        PostgreSQLContainer<?> postgres = Containers.postgres();
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        migrate(dataSource, SCHEMA, "store_it_changelog");
    }

    static void migrate(PGSimpleDataSource target, String schema, String changelogTable) throws Exception {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(target);
        liquibase.setResourceLoader(new DefaultResourceLoader());
        liquibase.setChangeLog("classpath:db/changelog/messaging-db/messaging-db-changelog.xml");
        liquibase.setChangeLogParameters(Map.of(MessagingDbLiquibaseAutoConfiguration.SCHEMA_PARAMETER, schema));
        liquibase.setDatabaseChangeLogTable(changelogTable);
        liquibase.setDatabaseChangeLogLockTable(changelogTable + "_lock");
        liquibase.afterPropertiesSet();
    }

    @BeforeEach
    void emptyTheLog() {
        jdbc.execute("TRUNCATE " + SCHEMA + ".message_log, " + SCHEMA + ".partition_head, " + SCHEMA
                + ".consumer_offset");
        store = new LogStore(dataSource, new ObjectMapper(), SCHEMA, PARTITIONS);
    }

    @Test
    void aKeyLandsOnThePartitionKafkasDefaultPartitionerPicks() {
        for (String key : List.of("o-1", "o-2", "customer-17", "ключ", "")) {
            byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
            assertThat(store.partitionFor(bytes)).as(key)
                    .isEqualTo(BuiltInPartitioner.partitionForKey(bytes, PARTITIONS));
        }
        assertThat(store.partitionFor(null)).isZero();
    }

    @Test
    void offsetsAreContiguousPerPartitionAndHeadersRoundTrip() {
        RecordHeaders headers = new RecordHeaders();
        headers.add("text", "значение".getBytes(StandardCharsets.UTF_8));
        headers.add("binary", new byte[] {0, (byte) 0xFF, 1});
        headers.add("text", "repeated".getBytes(StandardCharsets.UTF_8));

        List<Long> offsets = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            offsets.add(store.append("t", bytes("k"), headers, bytes("v" + i)).offset());
        }
        assertThat(offsets).containsExactly(0L, 1L, 2L);

        store.ensurePositions("g", List.of("t"), true);
        AtomicReference<StoredRecord> seen = new AtomicReference<>();
        store.processNext("g", List.of("t"), (position, record) -> {
            seen.set(record);
            return new Outcome.Advance();
        });
        assertThat(seen.get().offset()).isZero();
        assertThat(seen.get().payload()).isEqualTo(bytes("v0"));
        assertThat(seen.get().headers().toArray()).hasSize(3);
        assertThat(seen.get().headers().headers("text")).extracting(h -> new String(h.value(), StandardCharsets.UTF_8))
                .containsExactly("значение", "repeated");
        assertThat(seen.get().headers().lastHeader("binary").value()).containsExactly(0, (byte) 0xFF, 1);
    }

    /**
     * The gap a sequence would leave: a writer that took offset n and has not committed must hold back the
     * writer that would take n + 1, or a consumer could pass n before it is visible and never see it.
     */
    @Test
    void aSecondWriterWaitsForTheFirstToCommit() throws Exception {
        int partition = store.partitionFor(bytes("k"));
        try (Connection first = dataSource.getConnection()) {
            first.setAutoCommit(false);
            try (var statement = first.prepareStatement("INSERT INTO " + SCHEMA + ".partition_head"
                    + " (topic, log_partition, next_offset) VALUES ('t', ?, 1) ON CONFLICT (topic, log_partition)"
                    + " DO UPDATE SET next_offset = " + SCHEMA + ".partition_head.next_offset + 1")) {
                statement.setInt(1, partition);
                statement.executeUpdate();
            }
            CompletableFuture<LogStore.Appended> second =
                    CompletableFuture.supplyAsync(() -> store.append("t", bytes("k"), new RecordHeaders(), bytes("b")));
            Thread.sleep(500);
            assertThat(second).as("the second writer is blocked on the head row").isNotDone();
            first.commit();
            assertThat(second.get(10, TimeUnit.SECONDS).offset()).isEqualTo(1L);
        }
    }

    @Test
    void aClaimedPartitionIsSkippedByAnotherWorkerOfTheSameGroup() throws Exception {
        store.append("t", bytes("k"), new RecordHeaders(), bytes("a"));
        store.ensurePositions("g", List.of("t"), true);
        CountDownLatch inHandler = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Boolean> holder = CompletableFuture.supplyAsync(() ->
                store.processNext("g", List.of("t"), (position, record) -> {
                    inHandler.countDown();
                    await(release);
                    return new Outcome.Advance();
                }));
        assertThat(inHandler.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(store.processNext("g", List.of("t"), (position, record) -> {
            throw new AssertionError("the partition is claimed and must be skipped");
        })).isFalse();
        // Another group is not affected by this group's claim.
        store.ensurePositions("other", List.of("t"), true);
        assertThat(store.processNext("other", List.of("t"), (position, record) -> new Outcome.Advance())).isTrue();

        release.countDown();
        assertThat(holder.get(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void aHandlerThatDiesLeavesThePositionForTheNextPoll() {
        store.append("t", bytes("k"), new RecordHeaders(), bytes("a"));
        store.ensurePositions("g", List.of("t"), true);

        assertThatThrownBy(() -> store.processNext("g", List.of("t"), (position, record) -> {
            throw new IllegalStateException("instance died mid-record");
        })).isInstanceOf(IllegalStateException.class);

        AtomicReference<Long> redelivered = new AtomicReference<>();
        assertThat(store.processNext("g", List.of("t"), (position, record) -> {
            redelivered.set(record.offset());
            return new Outcome.Advance();
        })).isTrue();
        assertThat(redelivered.get()).isZero();
        assertThat(store.processNext("g", List.of("t"), (position, record) -> new Outcome.Advance())).isFalse();
    }

    @Test
    void aRetryHoldsThePartitionAndCountsTheFailure() {
        store.append("t", bytes("k"), new RecordHeaders(), bytes("a"));
        store.ensurePositions("g", List.of("t"), true);

        store.processNext("g", List.of("t"), (position, record) ->
                new Outcome.Retry(position.attempts() + 1, Duration.ofSeconds(30), "java.lang.IllegalStateException"));

        assertThat(store.processNext("g", List.of("t"), (position, record) -> new Outcome.Advance()))
                .as("held for 30s").isFalse();
        assertThat(jdbc.queryForMap("SELECT attempts, last_error FROM " + SCHEMA + ".consumer_offset"
                + " WHERE group_id = 'g' AND log_partition = ?", store.partitionFor(bytes("k"))))
                .containsEntry("attempts", 1).containsEntry("last_error", "java.lang.IllegalStateException");
    }

    @Test
    void aNewGroupStartsWhereOffsetResetSays() {
        store.append("t", bytes("k"), new RecordHeaders(), bytes("old"));
        store.ensurePositions("from-earliest", List.of("t"), true);
        store.ensurePositions("from-latest", List.of("t"), false);
        store.append("t", bytes("k"), new RecordHeaders(), bytes("new"));

        assertThat(drain("from-earliest")).containsExactly("old", "new");
        assertThat(drain("from-latest")).containsExactly("new");

        // Running again on restart changes nothing: positions that exist are kept.
        store.ensurePositions("from-latest", List.of("t"), true);
        assertThat(drain("from-latest")).isEmpty();
    }

    @Test
    void lagCountsWhatEachGroupHasNotConsumed() {
        for (int i = 0; i < 5; i++) {
            store.append("t", bytes("k" + i), new RecordHeaders(), bytes("v" + i));
        }
        store.ensurePositions("slow", List.of("t"), true);
        store.ensurePositions("fast", List.of("t"), true);
        drain("fast");

        assertThat(store.lag()).extracting(LogStore.Lag::groupId, LogStore.Lag::records)
                .contains(tuple("slow", 5L), tuple("fast", 0L));
    }

    @Test
    void thePurgeReportsUnconsumedRecordsThenDeletesEverythingOlderThanTheCutoff() {
        store.append("t", bytes("k"), new RecordHeaders(), bytes("old"));
        store.append("t", bytes("k"), new RecordHeaders(), bytes("recent"));
        store.ensurePositions("g", List.of("t"), true);
        jdbc.update("UPDATE " + SCHEMA + ".message_log SET produced_at = now() - interval '8 days'"
                + " WHERE payload = ?", (Object) bytes("old"));
        Instant cutoff = Instant.now().minus(Duration.ofDays(7));

        assertThat(store.unconsumedBefore(cutoff)).extracting(LogStore.Lag::groupId, LogStore.Lag::records)
                .containsExactly(tuple("g", 1L));
        assertThat(store.purgeBefore(cutoff)).isEqualTo(1);
        assertThat(drain("g")).containsExactly("recent");
    }

    @Test
    void anUnsafeSchemaNameIsRefused() {
        assertThatThrownBy(() -> new LogStore(dataSource, new ObjectMapper(), "x; DROP TABLE y", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LogStore(dataSource, new ObjectMapper(), SCHEMA, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<String> drain(String group) {
        List<String> payloads = new ArrayList<>();
        while (store.processNext(group, List.of("t"), (position, record) -> {
            payloads.add(new String(record.payload(), StandardCharsets.UTF_8));
            return new Outcome.Advance();
        })) {
            // keep draining
        }
        return payloads;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
