package ru.ludwigandreas.pat.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.ludwigandreas.testsupport.image.LudwigTestImages;

/**
 * The changelog itself: that it applies, that it is idempotent, and that the indexes it promises exist.
 *
 * <p>Run outside Spring and against the changelog directly, which is the shape
 * {@code idempotency-spring-boot-starter}'s migration test already uses. A {@code @SpringBootTest} would
 * prove the changelog works <em>given everything else in the context</em>; this proves the changelog on its
 * own, which is what a deployment applying it from a parent changelog actually does.
 *
 * <h2>Why the indexes are asserted and not just the table</h2>
 *
 * <p>Three of them are load-bearing in ways a schema diff would not show:
 *
 * <ul>
 *   <li>{@code ux_ludwig_pat_key_id} is what makes verification a <b>point read</b>. Without it the hot
 *       path becomes a sequential scan that works perfectly in a test with four rows and degrades
 *       continuously in production.</li>
 *   <li>{@code ux_ludwig_pat_previous_key_id} must be <b>partial</b>. A non-partial unique index would
 *       permit exactly <em>one</em> row in the whole table with a null previous key id - which is every
 *       token that has never been rotated. The second token issued would fail to insert.</li>
 *   <li>Both must be <b>unique</b> rather than merely indexed, so a key-id collision is a refused insert
 *       rather than two tokens that can verify against each other's digest.</li>
 * </ul>
 *
 * <p>The partial-index assertion is the one worth having a test for at all: it is a property of the
 * {@code WHERE} clause, Liquibase has no portable element for it, and getting it wrong produces a schema
 * that applies cleanly and then refuses the second token anybody issues.
 */
@Testcontainers
class PatSchemaIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(LudwigTestImages.POSTGRES);

    private static final String CHANGELOG = "classpath:db/changelog/pat/pat-changelog.xml";

    private static DataSource dataSource;

    @BeforeAll
    static void applyChangelog() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        source.setDriverClassName(POSTGRES.getDriverClassName());
        dataSource = source;
        runLiquibase();
    }

    private static void runLiquibase() throws Exception {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(CHANGELOG);
        liquibase.setResourceLoader(new DefaultResourceLoader());
        liquibase.setShouldRun(true);
        liquibase.afterPropertiesSet();
    }

    /**
     * Empties the table between tests.
     *
     * <p>Necessary because the container and therefore the table are shared across this class - and the
     * absence of this was a real test defect rather than a precaution: {@code twoNeverRotatedTokensCanCoexist}
     * counted rows and saw the ones {@code duplicateKeyIdIsRefused} had left behind, so it failed for a
     * reason that had nothing to do with the property under test. A shared-fixture test that counts
     * anything needs this or it is asserting on execution order.
     */
    @org.junit.jupiter.api.BeforeEach
    void emptyTable() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("delete from ludwig_pat");
        }
    }

    @Test
    @DisplayName("the changelog applies and creates the token table with every column the entity maps")
    void changelogCreatesTheTable() throws Exception {
        Map<String, String> columns = columnsOf("ludwig_pat");

        // Named explicitly rather than counted. A count passes when a column is renamed, and a renamed
        // column is exactly what breaks ddl-auto=validate in a deployment and nowhere else.
        assertThat(columns).containsKeys(
                "id", "owner_subject", "name",
                "key_id", "secret_digest",
                "previous_key_id", "previous_secret_digest", "previous_secret_expires_at",
                "scopes", "audiences", "allowed_cidrs",
                "created_at", "created_by", "updated_at", "updated_by",
                "expires_at", "revoked_at", "revocation_reason",
                "last_used_at", "last_used_ip", "use_count",
                "version");
    }

    @Test
    @DisplayName("the digests are nullable, which is what lets a revoked record be kept without its secret")
    void digestsAreNullable() throws Exception {
        Map<String, String> nullability = nullabilityOf("ludwig_pat");

        // Not an oversight. A revoked or expired token keeps its row with the digests CLEARED, so the
        // record still says what that credential could do while no longer being able to authenticate.
        assertThat(nullability).containsEntry("secret_digest", "YES");
        assertThat(nullability).containsEntry("previous_secret_digest", "YES");

        // These are not nullable, because a row without them is not a token.
        assertThat(nullability).containsEntry("owner_subject", "NO");
        assertThat(nullability).containsEntry("key_id", "NO");
        assertThat(nullability).containsEntry("scopes", "NO");
        assertThat(nullability).containsEntry("audiences", "NO");
    }

    @Test
    @DisplayName("both key-id indexes exist and are unique, and the previous-key-id one is partial")
    void keyIdIndexesAreUniqueAndThePreviousOneIsPartial() throws Exception {
        Map<String, String> indexes = indexDefinitions("ludwig_pat");

        assertThat(indexes).containsKey("ux_ludwig_pat_key_id");
        assertThat(indexes.get("ux_ludwig_pat_key_id"))
                .as("verification must be a point read on a unique index, not a scan")
                .contains("UNIQUE")
                .contains("KEY_ID");

        assertThat(indexes).containsKey("ux_ludwig_pat_previous_key_id");
        assertThat(indexes.get("ux_ludwig_pat_previous_key_id"))
                .as("a non-partial unique index would permit exactly one never-rotated token in the"
                        + " whole table, so the second token issued would fail to insert")
                .contains("UNIQUE")
                .contains("WHERE");
    }

    @Test
    @DisplayName("a second token with a null previous key id inserts, which the partial index is for")
    void twoNeverRotatedTokensCanCoexist() throws Exception {
        insertToken("key-a");
        insertToken("key-b");

        // The assertion the partial index exists for, made against the behaviour rather than the DDL. A
        // reader who changes the index and keeps the DDL assertion passing would still fail here.
        assertThat(countTokens()).isEqualTo(2);
    }

    @Test
    @DisplayName("a duplicate key id is refused by the database, not merely by the application")
    void duplicateKeyIdIsRefused() throws Exception {
        insertToken("key-duplicate");

        assertThatThrownBy(() -> insertToken("key-duplicate"))
                .as("a collision must be a refused insert rather than two tokens that can verify against"
                        + " each other's digest")
                .isInstanceOf(java.sql.SQLException.class);
    }

    @Test
    @DisplayName("applying the changelog twice is a no-op, so a restart does not fail")
    void changelogIsIdempotent() throws Exception {
        // Liquibase tracks applied changesets, so this should do nothing. Asserted because the failure
        // mode - a changeset without a usable checksum, or raw DDL that is not guarded - surfaces as a
        // pod that will not restart, which is discovered at the worst possible moment.
        runLiquibase();

        assertThat(columnsOf("ludwig_pat")).containsKey("key_id");
        assertThat(indexDefinitions("ludwig_pat")).containsKey("ux_ludwig_pat_previous_key_id");
    }

    private static void insertToken(String keyId) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "insert into ludwig_pat (id, owner_subject, name, key_id, secret_digest, scopes,"
                            + " audiences, created_at, use_count, version) values"
                            + " (gen_random_uuid(), 'alice', 'ci', '" + keyId + "', 'digest',"
                            + " 'orders:read', 'deploy-service', now(), 0, 0)");
        }
    }

    private static int countTokens() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("select count(*) from ludwig_pat")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static Map<String, String> columnsOf(String table) throws Exception {
        return queryToMap("select column_name, data_type from information_schema.columns"
                + " where table_name = '" + table + "'");
    }

    private static Map<String, String> nullabilityOf(String table) throws Exception {
        return queryToMap("select column_name, is_nullable from information_schema.columns"
                + " where table_name = '" + table + "'");
    }

    private static Map<String, String> indexDefinitions(String table) throws Exception {
        return queryToMap("select indexname, upper(indexdef) from pg_indexes"
                + " where tablename = '" + table + "'");
    }

    private static Map<String, String> queryToMap(String sql) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                result.put(rows.getString(1), rows.getString(2));
            }
        }
        return result;
    }
}
