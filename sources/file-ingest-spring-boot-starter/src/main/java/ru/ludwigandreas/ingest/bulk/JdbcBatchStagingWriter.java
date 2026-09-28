package ru.ludwigandreas.ingest.bulk;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.DataSourceUtils;
import ru.ludwigandreas.ingest.exception.IngestException;

/**
 * Writes staging rows with a batched {@code INSERT}.
 *
 * <p>The portable half of the staging write. Slower than {@code COPY} - materially so above a few
 * hundred thousand rows - and correct on every database with a JDBC driver, which is what keeps this
 * module from being Postgres-only by accident. A service on SQL Server or Oracle gets this one, gets
 * it automatically, and gets a working ingest.
 *
 * <h2>Why the SQL is built here rather than expressed in QueryDSL</h2>
 *
 * <p>QueryDSL's JPA module has no insert over a table it has no entity for, and a staging table
 * belongs to the author's schema: this module never sees its entity, only its name and its columns.
 * Generating the statement from those is the only option, and it is why this class is in
 * {@code ru.ludwigandreas.ingest.bulk} rather than anywhere else - see this package's documentation
 * for the carve-out and its conditions.
 *
 * <h2>The identifiers are validated, not escaped</h2>
 *
 * <p>The table and column names are interpolated into the statement, because a placeholder cannot
 * stand for an identifier in any SQL dialect. They come from the author's own
 * {@code RecordApplier}, not from a request, so this is not a live injection path - but a component
 * that concatenates identifiers into SQL is exactly the one that later receives a configured name,
 * and a check added after that change is one nobody remembers to add. {@link #requireIdentifier} is
 * therefore applied to every one of them, and refuses anything that is not a plain unquoted
 * identifier. <b>Values are always bound as parameters and never interpolated.</b>
 */
@Slf4j
public class JdbcBatchStagingWriter implements StagingWriter {

    private final DataSource dataSource;
    private final int batchSize;

    /**
     * Creates the writer.
     *
     * @param dataSource the application's data source; the connection is taken through
     *                   {@code DataSourceUtils} so that the write joins the engine's transaction
     *                   rather than opening one of its own
     * @param batchSize  rows per JDBC batch
     */
    public JdbcBatchStagingWriter(DataSource dataSource, int batchSize) {
        this.dataSource = dataSource;
        this.batchSize = batchSize;
    }

    @Override
    public int write(String table, List<String> columns, List<Object[]> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        String sql = insertStatement(table, columns);
        // DataSourceUtils, not dataSource.getConnection(): this must run on the connection the
        // engine's checkpoint transaction is bound to, or the rows and the checkpoint that claims
        // them would commit separately - which is the one thing IngestRunner exists to prevent.
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int written = 0;
            int pending = 0;
            for (Object[] row : rows) {
                for (int i = 0; i < row.length; i++) {
                    statement.setObject(i + 1, row[i]);
                }
                statement.addBatch();
                pending++;
                if (pending >= batchSize) {
                    written += count(statement.executeBatch());
                    pending = 0;
                }
            }
            if (pending > 0) {
                written += count(statement.executeBatch());
            }
            return written;
        } catch (SQLException e) {
            throw new IngestException("Could not write a staging batch into " + table, e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    @Override
    public void truncate(String table) {
        String sql = "DELETE FROM " + requireIdentifier(table);
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            // DELETE rather than TRUNCATE, for the portable writer. TRUNCATE is not transactional on
            // every database this writer exists to support, and a truncate that survived the
            // engine's rollback would empty a staging table whose checkpoint still claimed its rows.
            int removed = statement.executeUpdate();
            log.debug("Cleared {} stale staging row(s) from {}", removed, table);
        } catch (SQLException e) {
            throw new IngestException("Could not clear the staging table " + table, e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    /**
     * Builds {@code INSERT INTO table (a, b) VALUES (?, ?)}.
     *
     * <p>Identifiers interpolated after validation, values always bound - see this class's note.
     */
    private String insertStatement(String table, List<String> columns) {
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(requireIdentifier(table)).append(" (");
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
                placeholders.append(", ");
            }
            sql.append(requireIdentifier(columns.get(i)));
            placeholders.append('?');
        }
        return sql.append(") VALUES (").append(placeholders).append(')').toString();
    }

    private static int count(int[] results) {
        int total = 0;
        for (int result : results) {
            // Statement.SUCCESS_NO_INFO is -2 and is what several drivers return for a batched
            // insert. Counting it as one row is correct here: the batch is a list of rows this
            // process built, so the number sent is known even when the driver will not say.
            total += result >= 0 ? result : 1;
        }
        return total;
    }

    /**
     * Refuses anything that is not a plain unquoted SQL identifier.
     *
     * @param identifier the table or column name
     * @return the same name
     * @throws IngestException if it could not safely be interpolated
     */
    public static String requireIdentifier(String identifier) {
        if (identifier == null || !identifier.matches("[A-Za-z_][A-Za-z0-9_$]*")) {
            throw new IngestException("Not a usable SQL identifier: " + identifier
                    + ". Staging table and column names are interpolated into the statement, because"
                    + " no SQL dialect lets a placeholder stand for an identifier, so they are"
                    + " restricted to plain unquoted names.");
        }
        return identifier;
    }
}
