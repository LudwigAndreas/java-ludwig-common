package ru.ludwigandreas.idempotency.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Case 9: every {@code notification_idempotency} row lands in the shared table, and the counts reconcile.
 *
 * <h2>Why this is a test and not a runbook step</h2>
 *
 * <p>Because starting the new table empty is not a smaller version of this migration, it is a different and
 * wrong deployment. Every key in the old table is a claim on work that has been done; with an empty table
 * every in-flight key is a duplicate the service will act on a second time - once, during the deploy that
 * introduces the module whose whole purpose is to stop that. For {@code notification-service} that is a second
 * email or a second chat message to a real person.
 *
 * <h2>Why it has no Spring context and its own schema</h2>
 *
 * <p>What is under test is a changelog running against a database that already has the legacy table, which is
 * a statement about SQL and Liquibase rather than about beans - and building the legacy table by hand is the
 * only way to have one, since the service that owned it is not on this module's classpath.
 *
 * <p>Its own schema because these cases drop and recreate {@code idempotency_claim}, and the container is
 * shared with the suites that are using it. A schema is the smallest thing that makes "drop the world and
 * migrate it again" safe next to them.
 */
class NotificationMigrationIT extends PostgresBackedTest {

    /** Where this suite's tables live, so that dropping them cannot disturb the other suites. */
    private static final String SCHEMA = "legacy_migration";

    /** The legacy table, exactly as {@code 0003-notification-platform-gaps.xml} created it. */
    private static final String LEGACY_DDL = """
            CREATE TABLE notification_idempotency (
                id uuid PRIMARY KEY,
                scope varchar(64) NOT NULL,
                idempotency_key varchar(255) NOT NULL,
                request_id uuid NOT NULL,
                created_at timestamp with time zone NOT NULL,
                expires_at timestamp with time zone NOT NULL,
                CONSTRAINT uk_notification_idempotency_key UNIQUE (scope, idempotency_key)
            )
            """;

    private static final String INSERT_LEGACY = """
            INSERT INTO notification_idempotency
                (id, scope, idempotency_key, request_id, created_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private JdbcTemplate jdbc;

    /** An empty schema per case, so no case can decide another. */
    @BeforeEach
    void freshSchema() {
        JdbcTemplate admin = new JdbcTemplate(dataSource(null));
        admin.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        admin.execute("CREATE SCHEMA " + SCHEMA);
        jdbc = new JdbcTemplate(dataSource(SCHEMA));
        jdbc.execute(LEGACY_DDL);
    }

    @Test
    @DisplayName("every legacy row lands in the shared table with its window and its owner intact")
    void rowsMigrateWithCountsReconciling() throws Exception {
        Instant created = Instant.parse("2026-01-01T10:00:00Z");
        Instant expires = created.plusSeconds(86_400);
        List<UUID> owners = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        for (int i = 0; i < owners.size(); i++) {
            jdbc.update(INSERT_LEGACY, UUID.randomUUID(), i == 0 ? "kafka" : "rest", "legacy-key-" + i,
                    owners.get(i), java.sql.Timestamp.from(created), java.sql.Timestamp.from(expires));
        }
        long before = count("notification_idempotency");
        assertThat(before).isEqualTo(3);

        migrate();

        assertThat(count("idempotency_claim"))
                .describedAs("the counts before and after the migration must reconcile")
                .isEqualTo(before);

        List<Map<String, Object>> migrated = jdbc.queryForList(
                "SELECT scope, idempotency_key, request_id, state, fingerprint, lease_expires_at,"
                        + " response_status, created_at, expires_at FROM idempotency_claim"
                        + " ORDER BY idempotency_key");
        assertThat(migrated).hasSize(3);
        assertThat(migrated).allSatisfy(row -> {
            // COMPLETED is what every row in the old table meant: that table had no state column because its
            // only claim mode was the transactional one, where a visible row describes committed work.
            assertThat(row.get("state")).isEqualTo("COMPLETED");
            // Null rather than fabricated. A made-up fingerprint would refuse the very retries these rows
            // exist to recognise, because a null fingerprint deliberately never mismatches.
            assertThat(row.get("fingerprint")).isNull();
            assertThat(row.get("lease_expires_at")).isNull();
            assertThat(row.get("response_status")).isNull();
            // The window carries over rather than being recomputed from now(), which would silently extend
            // every in-flight key by a whole TTL.
            assertThat(((java.sql.Timestamp) row.get("expires_at")).toInstant()).isEqualTo(expires);
            assertThat(((java.sql.Timestamp) row.get("created_at")).toInstant()).isEqualTo(created);
        });
        assertThat(migrated.stream().map(row -> row.get("request_id")))
                .describedAs("a duplicate arriving after the deploy must still be answered with the id of"
                        + " the request that was actually created")
                .containsExactlyInAnyOrderElementsOf(owners);
        assertThat(migrated.stream().map(row -> row.get("scope")))
                .containsExactlyInAnyOrder("kafka", "rest", "rest");
    }

    @Test
    @DisplayName("running the migration again moves nothing and fails nothing")
    void migrationIsIdempotent() throws Exception {
        jdbc.update(INSERT_LEGACY, UUID.randomUUID(), "rest", "once", UUID.randomUUID(),
                java.sql.Timestamp.from(Instant.now()),
                java.sql.Timestamp.from(Instant.now().plusSeconds(86_400)));

        migrate();
        migrate();

        // runAlways="true" re-evaluates the precondition on every boot, so the statement runs on every one;
        // the anti-joins are what make the second run a no-op instead of a unique-constraint violation that
        // would take the application's startup with it.
        assertThat(count("idempotency_claim")).isEqualTo(1);
    }

    @Test
    @DisplayName("a newer claim on the same key survives the migration rather than failing it")
    void aNewerClaimWins() throws Exception {
        migrate();

        UUID newer = UUID.randomUUID();
        jdbc.update("INSERT INTO idempotency_claim (id, scope, idempotency_key, request_id, state,"
                        + " created_at, expires_at) VALUES (?, 'rest', 'contested', ?, 'COMPLETED',"
                        + " now(), now() + interval '1 day')", UUID.randomUUID(), newer);
        jdbc.update(INSERT_LEGACY, UUID.randomUUID(), "rest", "contested", UUID.randomUUID(),
                java.sql.Timestamp.from(Instant.now()),
                java.sql.Timestamp.from(Instant.now().plusSeconds(86_400)));

        migrate();

        // Between one boot and the next a request may claim the same (scope, key) through the new table, and
        // that claim is newer and authoritative. Inserting the old row would violate the unique constraint
        // and fail the whole migration, taking startup with it - which is why there are two anti-joins.
        assertThat(jdbc.queryForObject("SELECT request_id FROM idempotency_claim WHERE idempotency_key ="
                + " 'contested'", UUID.class)).isEqualTo(newer);
        assertThat(count("idempotency_claim")).isEqualTo(1);
    }

    /** Applies this module's changelog exactly as its autoconfiguration does in a real application. */
    private void migrate() throws Exception {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource(SCHEMA));
        liquibase.setDefaultSchema(SCHEMA);
        liquibase.setChangeLog("classpath:db/changelog/idempotency/idempotency-changelog.xml");
        liquibase.setResourceLoader(new DefaultResourceLoader());
        liquibase.afterPropertiesSet();
    }

    private long count(String table) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }

    private static DataSource dataSource(String schema) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        if (schema != null) {
            source.setCurrentSchema(schema);
        }
        return source;
    }
}
