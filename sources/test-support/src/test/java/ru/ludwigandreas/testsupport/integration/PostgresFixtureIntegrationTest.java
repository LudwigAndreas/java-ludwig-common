package ru.ludwigandreas.testsupport.integration;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.ludwigandreas.testsupport.container.Containers;
import ru.ludwigandreas.testsupport.data.DatabaseCleaner;
import ru.ludwigandreas.testsupport.junit.LudwigPostgresTest;

/**
 * Proves the fixtures this module ships actually wire up.
 *
 * <p>Three things are asserted here that no unit test can reach, and each of them has already been got
 * wrong once in this repository's history:
 *
 * <ul>
 *   <li>that {@code @ServiceConnection} resolves at all against an image name carrying both a tag and a
 *       digest - it does not, unless the connection name is given explicitly, which is why
 *       {@code PostgresContainerConfiguration} spells it out;</li>
 *   <li>that the singleton is genuinely shared rather than restarted, which is what the whole module is
 *       for;</li>
 *   <li>that {@link DatabaseCleaner} truncates a table and leaves Liquibase's bookkeeping alone.</li>
 * </ul>
 */
@LudwigPostgresTest
class PostgresFixtureIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("the context is wired to the shared, digest-pinned container")
    void contextIsWiredToTheSharedContainer() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);

        // The same container the static holder handed out, not a second one Spring started itself.
        String url = jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getURL());
        assertThat(url).contains(String.valueOf(Containers.postgres().getFirstMappedPort()));
    }

    @Test
    @DisplayName("the cleaner empties a table but keeps Liquibase's bookkeeping")
    void cleanerTruncatesButSparesLiquibase() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS cleaner_probe (id SERIAL PRIMARY KEY, note TEXT)");
        // Stands in for Liquibase's own table: the cleaner must not touch anything matching the prefix,
        // because re-applying a recorded changeset against a schema that still has the objects fails.
        jdbc.execute("CREATE TABLE IF NOT EXISTS databasechangelog_probe (id TEXT PRIMARY KEY)");
        jdbc.update("INSERT INTO cleaner_probe (note) VALUES ('before')");
        jdbc.update("INSERT INTO databasechangelog_probe (id) VALUES ('changeset-1') "
                + "ON CONFLICT (id) DO NOTHING");

        new DatabaseCleaner(dataSource).clean();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM cleaner_probe", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM databasechangelog_probe", Integer.class))
                .as("a table matching the Liquibase prefix must survive")
                .isEqualTo(1);

        // RESTART IDENTITY: without it a test asserting on a generated id passes alone and fails in a suite.
        jdbc.update("INSERT INTO cleaner_probe (note) VALUES ('after')");
        assertThat(jdbc.queryForObject("SELECT id FROM cleaner_probe", Integer.class)).isEqualTo(1);

        jdbc.execute("DROP TABLE cleaner_probe");
        jdbc.execute("DROP TABLE databasechangelog_probe");
    }
}
