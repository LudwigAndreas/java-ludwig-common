package ru.ludwigandreas.ingest.bulk;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import ru.ludwigandreas.ingest.exception.IngestException;

/**
 * Writes staging rows with Postgres {@code COPY}.
 *
 * <p>The reason the SQL carve-out in this package exists. {@code COPY ... FROM STDIN} streams rows
 * into the table through one statement and one round trip, and at the row counts this module is built
 * for it is several times faster than batched {@code INSERT} - the difference between a twenty-minute
 * ingest and an hour one. Neither QueryDSL nor JPA has any expression for it; the driver's
 * {@code CopyManager} is the only way to reach it from Java.
 *
 * <h2>The batch is already in memory, and that is the bound</h2>
 *
 * <p>This class buffers the batch it is handed into a byte array and hands that to {@code COPY}. That
 * is not a violation of the module's "never hold the file in memory" rule: the batch is bounded by
 * both {@code batch.max-records} and {@code batch.max-bytes} before it ever reaches here, so the
 * buffer is bounded by the same numbers. What it must never become is a writer that accumulates
 * across batches, which is why it takes a {@code List} and returns rather than exposing a stream a
 * caller could feed indefinitely.
 *
 * <h2>The text format, and the escaping that makes it safe</h2>
 *
 * <p>{@code COPY} in text format is delimiter-separated with backslash escapes and {@code \N} for
 * null. The escaping is done here rather than left to a library because there is no library: the
 * driver's {@code CopyManager} takes bytes, and getting this wrong does not produce an error, it
 * produces a row split in the wrong place. {@link #escape} handles every character the format gives
 * meaning to - backslash first, so that escapes introduced afterwards are not escaped again.
 */
@Slf4j
public class CopyStagingWriter implements StagingWriter {

    /** The text format's field separator. */
    private static final char DELIMITER = '\t';

    /** The text format's null marker. */
    private static final String NULL_MARKER = "\\N";

    private final DataSource dataSource;

    /**
     * Creates the writer.
     *
     * @param dataSource the application's data source
     */
    public CopyStagingWriter(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Whether this writer can be used on the given data source.
     *
     * <p>Asked once, at startup, rather than per batch: a connection that is not a
     * {@code PGConnection} is not going to become one, and discovering it at six-thirty in the
     * morning on the first batch of a two-hour run is the wrong time.
     *
     * @param dataSource the data source to test
     * @return {@code true} if its connections unwrap to a Postgres connection
     */
    public static boolean supports(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isWrapperFor(PGConnection.class);
        } catch (SQLException e) {
            log.debug("Could not determine whether COPY is available: {}", e.toString());
            return false;
        }
    }

    @Override
    public int write(String table, List<String> columns, List<Object[]> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        String sql = copyStatement(table, columns);
        // DataSourceUtils, not dataSource.getConnection(): the rows must land on the connection the
        // engine's checkpoint transaction is bound to, or they and the checkpoint that claims them
        // would commit separately - the one failure IngestRunner exists to prevent.
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            CopyManager copyManager = connection.unwrap(PGConnection.class).getCopyAPI();
            byte[] payload = render(rows);
            try (InputStream in = new ByteArrayInputStream(payload)) {
                long written = copyManager.copyIn(sql, in);
                return Math.toIntExact(written);
            }
        } catch (SQLException | IOException e) {
            throw new IngestException("Could not COPY a staging batch into " + table, e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    @Override
    public void truncate(String table) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        // TRUNCATE rather than DELETE: on Postgres it is transactional, so it rolls back with the
        // engine's transaction like everything else, and it does not leave dead tuples for a staging
        // table that is emptied every single day.
        try (var statement = connection.prepareStatement(
                "TRUNCATE TABLE " + JdbcBatchStagingWriter.requireIdentifier(table))) {
            statement.execute();
            log.debug("Truncated the staging table {}", table);
        } catch (SQLException e) {
            throw new IngestException("Could not truncate the staging table " + table, e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    /**
     * Builds {@code COPY table (a, b) FROM STDIN}.
     *
     * <p>Identifiers are interpolated after validation, because no SQL dialect lets a placeholder
     * stand for one, and {@code COPY} takes no parameters at all - the values travel in the stream
     * rather than in the statement, which is exactly what makes it fast.
     */
    private String copyStatement(String table, List<String> columns) {
        StringBuilder sql = new StringBuilder("COPY ")
                .append(JdbcBatchStagingWriter.requireIdentifier(table)).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(JdbcBatchStagingWriter.requireIdentifier(columns.get(i)));
        }
        return sql.append(") FROM STDIN").toString();
    }

    private byte[] render(List<Object[]> rows) {
        StringBuilder out = new StringBuilder();
        for (Object[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                if (i > 0) {
                    out.append(DELIMITER);
                }
                out.append(row[i] == null ? NULL_MARKER : escape(String.valueOf(row[i])));
            }
            out.append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Escapes one field for {@code COPY}'s text format.
     *
     * <p>Backslash first. Escaping it after introducing the others would turn the backslash of, say,
     * {@code \t} into {@code \\t}, and the row would arrive with a literal backslash-t instead of a
     * tab - a corruption that looks like bad source data and is not.
     *
     * @param value the field
     * @return the escaped field
     */
    public static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
