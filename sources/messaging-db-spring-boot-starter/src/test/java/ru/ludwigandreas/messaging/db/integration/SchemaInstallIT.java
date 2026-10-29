package ru.ludwigandreas.messaging.db.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import ru.ludwigandreas.testsupport.container.Containers;

/**
 * Every service runs the log's changesets against the one shared database with its own changelog table, so
 * the second service - and two services starting at the same moment - must find the tables and carry on.
 */
class SchemaInstallIT {

    private static PGSimpleDataSource dataSource;

    @BeforeAll
    static void connect() {
        PostgreSQLContainer<?> postgres = Containers.postgres();
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
    }

    @Test
    void aSecondServiceWithItsOwnChangelogTableUsesTheExistingTables() throws Exception {
        LogStoreIT.migrate(dataSource, "shared_it", "service_a_changelog");
        new JdbcTemplate(dataSource).update("INSERT INTO shared_it.partition_head VALUES ('kept', 0, 7)");

        LogStoreIT.migrate(dataSource, "shared_it", "service_b_changelog");

        assertThat(tables("shared_it"))
                .containsExactlyInAnyOrder("message_log", "partition_head", "consumer_offset", "consumer_pause");
        assertThat(new JdbcTemplate(dataSource).queryForObject(
                "SELECT next_offset FROM shared_it.partition_head WHERE topic = 'kept'", Long.class)).isEqualTo(7L);
    }

    @Test
    void twoServicesStartingAtOnceBothSucceed() throws Exception {
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> migrateQuietly("race_a_changelog"));
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> migrateQuietly("race_b_changelog"));

        CompletableFuture.allOf(first, second).get(60, TimeUnit.SECONDS);

        assertThat(tables("race_it"))
                .containsExactlyInAnyOrder("message_log", "partition_head", "consumer_offset", "consumer_pause");
    }

    private static void migrateQuietly(String changelogTable) {
        try {
            LogStoreIT.migrate(dataSource, "race_it", changelogTable);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> tables(String schema) {
        return new JdbcTemplate(dataSource).queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ?", String.class, schema);
    }
}
