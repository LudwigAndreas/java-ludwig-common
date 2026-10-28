package ru.ludwigandreas.job.core.lock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * What an operator does to run leases: look at them, and free one whose holder is gone.
 *
 * <p>Separate from {@link RunLock} on purpose. Every scheduled job holds a {@code RunLock}; none of them should be
 * handed the power to break another job's lease. This class is what the administrative endpoints use, and nothing
 * else in the platform has a reason to.
 *
 * <p>Plain JDBC on short auto-commit connections, for the reason {@link JdbcRunLock} gives: a lease change must be
 * visible to every instance the moment it is made, and must never ride on - or be rolled back with - whatever
 * transaction the caller happens to be in.
 */
public class RunLockAdministration {

    private static final String LIST_SQL = """
            SELECT lock_name, owner, run_id, acquired_at, heartbeat_at, expires_at
              FROM job_run_lock
             ORDER BY lock_name
            """;

    private static final String FIND_SQL = """
            SELECT lock_name, owner, run_id, acquired_at, heartbeat_at, expires_at
              FROM job_run_lock
             WHERE lock_name = ?
            """;

    /**
     * Exactly a release, minus the condition that the caller is the holder: owner and run id nulled, expiry moved to
     * now, row kept. Restricted to a lease that is held, so freeing an idle lock changes nothing and says so. The
     * {@code RETURNING} of the previous holder is what the audit record names.
     */
    private static final String FORCE_RELEASE_SQL = """
            UPDATE job_run_lock AS t
               SET owner = NULL, run_id = NULL, expires_at = now()
              FROM (SELECT lock_name, owner, run_id, acquired_at, heartbeat_at, expires_at
                      FROM job_run_lock WHERE lock_name = ? AND owner IS NOT NULL FOR UPDATE) AS previous
             WHERE t.lock_name = previous.lock_name
            RETURNING previous.lock_name, previous.owner, previous.run_id, previous.acquired_at,
                      previous.heartbeat_at, previous.expires_at
            """;

    private final DataSource dataSource;

    /**
     * Creates the administration.
     *
     * @param dataSource the data source the leases live in
     */
    public RunLockAdministration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Every lease row, held or not, ordered by name.
     *
     * @return the leases as the table records them
     */
    public List<RunLease> leases() {
        try (Connection connection = openAutoCommit();
                PreparedStatement select = connection.prepareStatement(LIST_SQL);
                ResultSet rows = select.executeQuery()) {
            List<RunLease> leases = new ArrayList<>();
            while (rows.next()) {
                leases.add(read(rows));
            }
            return leases;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the run lease table", e);
        }
    }

    /**
     * One lease row.
     *
     * @param lockName the lease's name
     * @return the lease, or empty when no row has that name
     */
    public Optional<RunLease> lease(String lockName) {
        try (Connection connection = openAutoCommit();
                PreparedStatement select = connection.prepareStatement(FIND_SQL)) {
            select.setString(1, lockName);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read run lease '" + lockName + "'", e);
        }
    }

    /**
     * Frees a held lease whatever instance holds it.
     *
     * <p>A holder that is still alive fails its next renewal - renewal is conditional on owner and run id, both now
     * gone - and stops, exactly as it would after losing the lease to expiry. The work it did between its last
     * renewal and that moment may be repeated by the next holder, which is the same exposure an expiry has.
     *
     * @param lockName the lease's name
     * @return the lease as it was before the release, or empty when it was not held
     */
    public Optional<RunLease> forceRelease(String lockName) {
        try (Connection connection = openAutoCommit();
                PreparedStatement update = connection.prepareStatement(FORCE_RELEASE_SQL)) {
            update.setString(1, lockName);
            try (ResultSet rows = update.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not release run lease '" + lockName + "'", e);
        }
    }

    private Connection openAutoCommit() throws SQLException {
        Connection connection = dataSource.getConnection();
        if (!connection.getAutoCommit()) {
            connection.setAutoCommit(true);
        }
        return connection;
    }

    private static RunLease read(ResultSet rows) throws SQLException {
        return new RunLease(rows.getString("lock_name"), rows.getString("owner"),
                rows.getObject("run_id", UUID.class), instant(rows.getTimestamp("acquired_at")),
                instant(rows.getTimestamp("heartbeat_at")), instant(rows.getTimestamp("expires_at")));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /**
     * One lease row.
     *
     * @param lockName    the lease's name
     * @param owner       the instance holding it, or null when it is free
     * @param runId       the holding acquisition, or null when it is free
     * @param acquiredAt  when it was last acquired
     * @param heartbeatAt when its holder last renewed it
     * @param expiresAt   when it lapses unless renewed; in the past for a free lease
     */
    public record RunLease(String lockName, String owner, UUID runId, Instant acquiredAt, Instant heartbeatAt,
                           Instant expiresAt) {

        /**
         * Whether an instance holds the lease right now.
         *
         * @param now the current time
         * @return true when an owner is recorded and the lease has not lapsed
         */
        public boolean heldAt(Instant now) {
            return owner != null && expiresAt != null && expiresAt.isAfter(now);
        }
    }
}
