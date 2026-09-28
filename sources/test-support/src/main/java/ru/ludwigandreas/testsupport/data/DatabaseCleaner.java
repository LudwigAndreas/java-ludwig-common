package ru.ludwigandreas.testsupport.data;

import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Empties every table in the database except Liquibase's own, so a shared container behaves like a
 * fresh one.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>It is the price of {@link ru.ludwigandreas.testsupport.container.Containers}. Nineteen
 * per-class containers gave every test class an empty database by construction; one shared container
 * does not, so isolation moves from the container to the schema and something has to make that cheap.
 *
 * <p>It is not merely tidiness. {@code DeliveryQueueConcurrencyTest} had already hand-rolled this and
 * explained why, and the reasoning generalises to every module here: when what a test exercises <em>is</em>
 * shared cluster-wide state, a leftover row is not a tidy-up detail but a test that passes or fails
 * depending on what ran before it. A lock left held by an earlier case blocks the next one; a
 * rate-limit window from an earlier case has already spent the budget the next one is measuring. The
 * failure surfaces as order-dependence, which is the hardest kind of flake to attribute.
 *
 * <h2>Why {@code TRUNCATE ... RESTART IDENTITY CASCADE} rather than {@code DELETE}</h2>
 *
 * <p>{@code TRUNCATE} of all tables in one statement is substantially faster than a row-by-row
 * {@code DELETE} and does not have to be ordered to respect foreign keys. {@code CASCADE} is what lets
 * one statement name every table regardless of references between them; {@code RESTART IDENTITY} resets
 * sequences, without which a test asserting on a generated id passes alone and fails in a suite.
 *
 * <h2>Why the Liquibase tables are excluded</h2>
 *
 * <p>{@code DATABASECHANGELOG} is the record of which changesets have run. Truncating it would make
 * the next context in the same JVM re-apply every changeset against a schema that still has the
 * objects, which fails on the first {@code CREATE TABLE}. {@code DATABASECHANGELOGLOCK} must survive
 * for the same reason.
 *
 * <h2>Why the SQL here is not a third carve-out from the QueryDSL-only rule</h2>
 *
 * <p>The platform's rule that data access goes through generated QueryDSL Q-types governs a module's
 * access to its <em>own</em> tables, and it has exactly two named exceptions
 * ({@code ru.ludwigandreas.ingest.bulk} and {@code ru.ludwigandreas.idempotency.sql}), each enforced
 * by a {@code SqlConfinementTest}. This class is neither a third exception nor a violation of the
 * rule, for two independent reasons.
 *
 * <p>First, there is no entity to query. {@code information_schema.tables} is a catalog view, and
 * {@code TRUNCATE} is DDL; QueryDSL-JPA generates JPQL, which has no way to express either, and
 * generating a Q-type would require mapping a system catalog as an {@code @Entity}. Second, this
 * module has no JPA on its classpath at all - it is test-scope infrastructure that owns no schema and
 * no domain. The rule exists so that a query against a column that was renamed fails at compile time;
 * there is no column here that a changeset could rename.
 *
 * <p>The table list is discovered from {@code information_schema} rather than declared, so a module
 * that adds a changeset does not also have to remember to add its table here - the omission would show
 * up as an order-dependent failure somewhere else entirely.
 */
public final class DatabaseCleaner {

    /**
     * Liquibase's bookkeeping, which must survive: see the class comment.
     *
     * <p>Matched with {@code LIKE 'databasechangelog%'} rather than by equality because Liquibase can
     * be configured to suffix both tables, and a deployment that has done so would otherwise have them
     * truncated.
     */
    private static final String EXCLUDED = "databasechangelog%";

    private final JdbcTemplate jdbc;

    /**
     * Creates a cleaner over a data source.
     *
     * @param dataSource the data source, normally the one the test context is wired to
     */
    public DatabaseCleaner(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * Truncates every table in the {@code public} schema except Liquibase's.
     *
     * <p>Safe to call when there is nothing to do: with no tables it issues no statement rather than
     * a {@code TRUNCATE} with an empty list, which is a syntax error.
     */
    public void clean() {
        clean("public");
    }

    /**
     * Truncates every table in one schema except Liquibase's.
     *
     * @param schema the schema to empty
     */
    public void clean(String schema) {
        List<String> tables = jdbc.queryForList(
                """
                SELECT table_name
                  FROM information_schema.tables
                 WHERE table_schema = ?
                   AND table_type = 'BASE TABLE'
                   AND lower(table_name) NOT LIKE ?
                """,
                String.class, schema, EXCLUDED);
        if (tables.isEmpty()) {
            return;
        }
        String targets = tables.stream()
                .map(table -> "\"" + schema + "\".\"" + table + "\"")
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow();
        jdbc.execute("TRUNCATE TABLE " + targets + " RESTART IDENTITY CASCADE");
    }
}
