package ru.ludwigandreas.ingest.bulk;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.DataSourceUtils;
import ru.ludwigandreas.ingest.exception.IngestException;

/**
 * Runs the author's merge statement once, moving staging into the target.
 *
 * <h2>Why one set-based statement and not a loop</h2>
 *
 * <p>Three properties follow from doing it in one statement, and none of them survives doing it a row
 * at a time:
 *
 * <ul>
 *   <li><b>The target changes atomically.</b> Nothing ever observes a half-imported catalogue -
 *       not a report running at the same time, not a screen, not another service reading through an
 *       API. A row-by-row merge has a window, and the window is as long as the merge.</li>
 *   <li><b>The parse phase runs free of the target's constraints.</b> Foreign keys and unique
 *       indexes are checked here, at the end, against rows that are already staged - so one violating
 *       row does not abort a transaction carrying five thousand good ones.</li>
 *   <li><b>The counts are checkable.</b> The statement reports how many rows it touched, and that
 *       number is what the balance check reconciles against what was staged.</li>
 * </ul>
 *
 * <h2>Why QueryDSL cannot express it</h2>
 *
 * <p>The statement is {@code INSERT INTO target (...) SELECT ... FROM staging ... ON CONFLICT (...)
 * DO UPDATE SET ...}. QueryDSL's JPA module has no insert-from-select at all - its {@code SQLInsert}
 * lives in the SQL module, which needs a generated schema this module cannot have, because the target
 * belongs to the author's application and is invisible here. Even with one, {@code ON CONFLICT} is a
 * Postgres extension QueryDSL does not model.
 *
 * <p>The alternative is to read the staged rows into this process and write them back one at a time,
 * which loses all three properties above and reintroduces the memory bound the module is built to
 * respect. That is the trade the carve-out in this package's documentation was granted for.
 *
 * <h2>The statement comes from the author, and is not built here</h2>
 *
 * <p>{@code RecordApplier#mergeStatement()} returns it whole. This class does not compose it, does not
 * interpolate anything into it, and passes no parameters to it - so there is nothing here for a value
 * to be injected through. What the author writes is reviewed as part of their own service, exactly as
 * the native {@code @Query} statements in {@code DistributedLockRepository} are.
 */
@Slf4j
public class StagingMerge {

    private final DataSource dataSource;

    /**
     * Creates the merge runner.
     *
     * @param dataSource the application's data source
     */
    public StagingMerge(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Runs the merge.
     *
     * <p>Inside the caller's transaction, through {@code DataSourceUtils}, so that a merge which
     * fails leaves the target untouched - which is what lets the balance check gate {@code COMPLETED}
     * rather than merely report on it.
     *
     * @param statement the author's merge statement
     * @return how many target rows it affected
     */
    public int merge(String statement) {
        if (statement == null || statement.isBlank()) {
            throw new IngestException("A task's merge statement is required: without it the staged rows"
                    + " would never reach the target and the run would report success having changed"
                    + " nothing");
        }
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try (Statement jdbc = connection.createStatement()) {
            int affected = jdbc.executeUpdate(statement);
            log.debug("Merge affected {} target row(s)", affected);
            return affected;
        } catch (SQLException e) {
            throw new IngestException("The staging merge failed; the target is unchanged", e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    /**
     * Counts what is currently staged, for the balance check.
     *
     * <p>A {@code COUNT(*)} rather than trusting the engine's own tally, and the difference is the
     * point: the engine's number is what it believes it wrote, and this is what is actually there. A
     * balance check that compared the engine's count to the engine's count would prove nothing.
     *
     * @param table the staging table
     * @return how many rows are in it
     */
    public long countStaged(String table) {
        String sql = "SELECT count(*) FROM " + JdbcBatchStagingWriter.requireIdentifier(table);
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try (Statement jdbc = connection.createStatement();
                var results = jdbc.executeQuery(sql)) {
            return results.next() ? results.getLong(1) : 0;
        } catch (SQLException e) {
            throw new IngestException("Could not count the staging table " + table, e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }
}
