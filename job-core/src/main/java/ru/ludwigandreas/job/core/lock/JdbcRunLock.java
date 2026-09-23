package ru.ludwigandreas.job.core.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ludwigandreas.job.core.claim.SkipLockedClaim;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link RunLock} backed by a single {@code job_run_lock} row per lock name.
 *
 * <h2>Why plain JDBC on its own connection</h2>
 *
 * <p>A lease is only useful if other instances can see it, which means it has to be committed the
 * moment it is taken. Acquiring it through the caller's {@code EntityManager} would enlist it in the
 * caller's transaction: invisible to everyone else until that transaction commits, and rolled back
 * along with it - so a run that failed and rolled back would also silently give up a lease it should
 * have kept long enough for its outcome to be recorded. Taking a short auto-commit connection of its
 * own makes each lease operation atomic, immediately visible, and independent of whatever the caller
 * is doing transactionally.
 *
 * <h2>Why the acquire is a SKIP LOCKED claim</h2>
 *
 * <p>Every other claim in this platform is a {@code FOR UPDATE SKIP LOCKED} claim, and the reason
 * applies here too: two instances reaching for the same lease at the same instant must not queue
 * behind each other - the loser has nothing to wait for, it simply did not get the lease. Skipping
 * the locked row turns the contended case into an immediate "no" instead of a stall proportional to
 * however long the winner's own statement takes.
 *
 * <p>The row is created on first use with an {@code ON CONFLICT DO NOTHING} insert whose expiry is
 * already in the past, so a brand-new lock is immediately claimable by whichever instance gets there
 * first, including the one that inserted it.
 */
public class JdbcRunLock implements RunLock {

    private static final Logger log = LoggerFactory.getLogger(JdbcRunLock.class);

    private static final String TABLE = "job_run_lock";

    /**
     * Creates the row if it is not there yet. {@code expires_at} is set to {@code now()} rather than
     * to a future instant, so the insert grants nothing - acquisition is always the claim below.
     */
    private static final String ENSURE_ROW_SQL = """
            INSERT INTO job_run_lock (id, lock_name, owner, run_id, acquired_at, heartbeat_at, expires_at)
            VALUES (?, ?, NULL, NULL, now(), now(), now())
            ON CONFLICT (lock_name) DO NOTHING
            """;

    private static final String CLAIM_SQL = SkipLockedClaim.sql(
            TABLE,
            "owner = ?, run_id = ?, acquired_at = now(), heartbeat_at = now(), "
                    + "expires_at = now() + make_interval(secs => ?)",
            "t.lock_name = ? AND t.expires_at <= now()",
            "t.lock_name",
            "1");

    /**
     * Renewal is conditional on still being the holder of <em>this</em> acquisition. Matching on
     * {@code run_id} as well as {@code owner} is what makes a lease that expired, was taken by
     * another instance and then came back to this one count as lost rather than as still held - the
     * owner string would match, and the work this process is part-way through is no longer the
     * authoritative run.
     */
    private static final String RENEW_SQL = """
            UPDATE job_run_lock
               SET heartbeat_at = now(), expires_at = now() + make_interval(secs => ?)
             WHERE lock_name = ? AND owner = ? AND run_id = ? AND expires_at > now()
            """;

    private static final String RELEASE_SQL = """
            UPDATE job_run_lock
               SET owner = NULL, run_id = NULL, expires_at = now()
             WHERE lock_name = ? AND owner = ? AND run_id = ?
            """;

    private final DataSource dataSource;
    private final String owner;

    /**
     * Creates the lock.
     *
     * @param dataSource data source short auto-commit connections are taken from
     * @param owner      this instance's identity, from {@code ClaimOwner.resolve(...)}
     */
    public JdbcRunLock(DataSource dataSource, String owner) {
        this.dataSource = dataSource;
        this.owner = owner;
    }

    @Override
    public Optional<RunLockHandle> tryAcquire(String lockName, Duration leaseTtl) {
        UUID runId = UUID.randomUUID();
        try (Connection connection = openAutoCommit()) {
            ensureRow(connection, lockName);
            try (PreparedStatement claim = connection.prepareStatement(CLAIM_SQL)) {
                // Bound by an incrementing index rather than by literals so that adding a column to
                // the statement above cannot silently shift a value into the wrong placeholder.
                int index = 0;
                claim.setString(++index, owner);
                claim.setObject(++index, runId);
                claim.setDouble(++index, toSeconds(leaseTtl));
                claim.setString(++index, lockName);
                try (ResultSet rows = claim.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.empty();
                    }
                }
            }
            log.debug("Acquired run lock '{}' as {} (run {})", lockName, owner, runId);
            return Optional.of(new Handle(lockName, runId));
        } catch (SQLException e) {
            // Losing a lease acquisition to a database blip must not kill the schedule: the tick
            // simply did not get the lock, and the next one will try again.
            log.warn("Could not acquire run lock '{}'; treating it as held elsewhere", lockName, e);
            return Optional.empty();
        }
    }

    private void ensureRow(Connection connection, String lockName) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(ENSURE_ROW_SQL)) {
            insert.setObject(1, UUID.randomUUID());
            insert.setString(2, lockName);
            insert.executeUpdate();
        }
    }

    private Connection openAutoCommit() throws SQLException {
        Connection connection = dataSource.getConnection();
        if (!connection.getAutoCommit()) {
            connection.setAutoCommit(true);
        }
        return connection;
    }

    private static double toSeconds(Duration duration) {
        return duration.toNanos() / (double) Duration.ofSeconds(1).toNanos();
    }

    /** The held lease; see {@link RunLockHandle}. */
    private final class Handle implements RunLockHandle {

        private final String lockName;
        private final UUID runId;
        private volatile boolean released;

        private Handle(String lockName, UUID runId) {
            this.lockName = lockName;
            this.runId = runId;
        }

        @Override
        public String lockName() {
            return lockName;
        }

        @Override
        public String owner() {
            return owner;
        }

        @Override
        public UUID runId() {
            return runId;
        }

        @Override
        public boolean renew(Duration leaseTtl) {
            if (released) {
                return false;
            }
            try (Connection connection = openAutoCommit();
                    PreparedStatement renew = connection.prepareStatement(RENEW_SQL)) {
                int index = 0;
                renew.setDouble(++index, toSeconds(leaseTtl));
                renew.setString(++index, lockName);
                renew.setString(++index, owner);
                renew.setObject(++index, runId);
                boolean stillHeld = renew.executeUpdate() == 1;
                if (!stillHeld) {
                    log.warn("Run lock '{}' was lost by {} (run {}): the lease expired and another "
                            + "instance may already be repeating this run", lockName, owner, runId);
                }
                return stillHeld;
            } catch (SQLException e) {
                // A failed renewal is indistinguishable from a lost one from the caller's point of
                // view, and must be treated as lost: continuing would risk two live runs.
                log.warn("Could not renew run lock '{}'; treating it as lost", lockName, e);
                return false;
            }
        }

        @Override
        public void close() {
            if (released) {
                return;
            }
            released = true;
            try (Connection connection = openAutoCommit();
                    PreparedStatement release = connection.prepareStatement(RELEASE_SQL)) {
                int index = 0;
                release.setString(++index, lockName);
                release.setString(++index, owner);
                release.setObject(++index, runId);
                release.executeUpdate();
                log.debug("Released run lock '{}' (run {})", lockName, runId);
            } catch (SQLException e) {
                // Not fatal: the lease expires on its own. The cost of a failed release is that the
                // next run of this job waits out the remaining TTL, which is why it is logged.
                log.warn("Could not release run lock '{}'; it will expire on its own", lockName, e);
            }
        }
    }
}
