package ru.ludwigandreas.job.core.control;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The replica-control tables: heartbeats, commands and acknowledgements. Plain JDBC on short auto-commit connections,
 * as {@code JdbcRunLock} is, so a heartbeat or an acknowledgement is visible to every replica at once. Every time
 * comparison uses the database's {@code now()}, never a replica's clock.
 */
public class ControlStore {

    /** One replica's heartbeat. */
    public record Instance(String owner, Instant startedAt, Instant lastSeenAt) {
    }

    /** A recorded command, with whether its deadline has passed by the database's clock. */
    public record Command(UUID id, String control, String argumentsJson, String operation, String submittedBy,
                          Instant submittedAt, Instant deadline, List<String> targets, boolean expired) {
    }

    /** One replica's acknowledgement. */
    public record Ack(String owner, Instant acknowledgedAt, String outcome, String failureCode, String resultJson) {
    }

    private static final String HEARTBEAT_SQL = "INSERT INTO job_instance (owner) VALUES (?)"
            + " ON CONFLICT (owner) DO UPDATE SET last_seen_at = now()";
    private static final String LIVE_SQL = "SELECT owner, started_at, last_seen_at FROM job_instance"
            + " WHERE last_seen_at > now() - make_interval(secs => ?) ORDER BY owner";
    private static final String INSERT_SQL = "INSERT INTO job_control_command"
            + " (id, control, arguments, operation, submitted_by, deadline, targets)"
            + " VALUES (?, ?, CAST(? AS jsonb), ?, ?, now() + make_interval(secs => ?), ?)";
    private static final String COMMAND_COLUMNS = "c.id, c.control, c.arguments::text AS arguments, c.operation,"
            + " c.submitted_by, c.submitted_at, c.deadline, c.targets, c.deadline <= now() AS expired";
    private static final String PENDING_SQL = "SELECT " + COMMAND_COLUMNS + " FROM job_control_command c"
            + " WHERE c.deadline > now() AND ? = ANY (c.targets)"
            + " AND NOT EXISTS (SELECT 1 FROM job_control_ack a WHERE a.command_id = c.id AND a.owner = ?)"
            + " ORDER BY c.submitted_at";
    private static final String FIND_SQL = "SELECT " + COMMAND_COLUMNS + " FROM job_control_command c WHERE c.id = ?";
    private static final String ACKS_SQL = "SELECT owner, acknowledged_at, outcome, failure_code,"
            + " result::text AS result"
            + " FROM job_control_ack WHERE command_id = ? ORDER BY owner";
    private static final String ACK_SQL = "INSERT INTO job_control_ack (command_id, owner, outcome, failure_code,"
            + " result) VALUES (?, ?, ?, ?, CAST(? AS jsonb)) ON CONFLICT DO NOTHING";
    private static final String PURGE_SQL = "DELETE FROM job_control_command WHERE id IN (SELECT id FROM"
            + " job_control_command WHERE submitted_at < now() - make_interval(secs => ?) LIMIT ?)";
    private static final String FORGET_SQL = "DELETE FROM job_instance"
            + " WHERE last_seen_at < now() - make_interval(secs => ?)";

    private static final double MILLIS_PER_SECOND = 1000.0;

    private final DataSource dataSource;

    /**
     * Creates the store.
     *
     * @param dataSource the database holding job-core's schema
     */
    public ControlStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Records that a replica is alive now.
     *
     * @param owner the replica
     */
    public void heartbeat(String owner) {
        update(HEARTBEAT_SQL, owner);
    }

    /**
     * The replicas seen within a window of the database's clock.
     *
     * @param window how recent a heartbeat counts as live
     * @return the live replicas, by owner
     */
    public List<Instance> live(Duration window) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement select = connection.prepareStatement(LIVE_SQL)) {
            select.setDouble(1, seconds(window));
            List<Instance> live = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    live.add(new Instance(rows.getString("owner"), rows.getTimestamp("started_at").toInstant(),
                            rows.getTimestamp("last_seen_at").toInstant()));
                }
            }
            return live;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read live replicas", e);
        }
    }

    /**
     * Records a command for the given targets.
     *
     * @param id            the command id
     * @param control       the control's name
     * @param argumentsJson the arguments as JSON
     * @param operation     the operation that authorized it
     * @param submittedBy   the caller's subject
     * @param ackTimeout    how long the targets have to acknowledge
     * @param targets       the replicas live at submission
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - one row's columns, each named at the one call site.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public void insert(UUID id, String control, String argumentsJson, String operation, String submittedBy,
                       Duration ackTimeout, List<String> targets) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement insert = connection.prepareStatement(INSERT_SQL)) {
            int index = 0;
            insert.setObject(++index, id);
            insert.setString(++index, control);
            insert.setString(++index, argumentsJson);
            insert.setString(++index, operation);
            insert.setString(++index, submittedBy);
            insert.setDouble(++index, seconds(ackTimeout));
            insert.setArray(++index, connection.createArrayOf("text", targets.toArray()));
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not record command " + id, e);
        }
    }

    /**
     * The commands a replica has still to apply: targeting it, within their deadline, and not acknowledged by it.
     *
     * @param owner the replica
     * @return the commands, oldest first
     */
    public List<Command> pendingFor(String owner) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement select = connection.prepareStatement(PENDING_SQL)) {
            select.setString(1, owner);
            select.setString(2, owner);
            return commands(select);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read pending commands", e);
        }
    }

    /**
     * One command.
     *
     * @param id the command id
     * @return it, or empty
     */
    public Optional<Command> find(UUID id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement select = connection.prepareStatement(FIND_SQL)) {
            select.setObject(1, id);
            return commands(select).stream().findFirst();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read command " + id, e);
        }
    }

    /**
     * A command's acknowledgements.
     *
     * @param id the command id
     * @return them, by owner
     */
    public List<Ack> acks(UUID id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement select = connection.prepareStatement(ACKS_SQL)) {
            select.setObject(1, id);
            List<Ack> acks = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    acks.add(new Ack(rows.getString("owner"), rows.getTimestamp("acknowledged_at").toInstant(),
                            rows.getString("outcome"), rows.getString("failure_code"), rows.getString("result")));
                }
            }
            return acks;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read acknowledgements of " + id, e);
        }
    }

    /**
     * Records a replica's acknowledgement; a second one for the same replica is ignored.
     *
     * @param id          the command id
     * @param owner       the replica
     * @param outcome     {@code SUCCEEDED} or {@code FAILED}
     * @param failureCode the failure code, or null
     * @param resultJson  the per-replica result as JSON, or null
     */
    public void ack(UUID id, String owner, String outcome, String failureCode, String resultJson) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement insert = connection.prepareStatement(ACK_SQL)) {
            int index = 0;
            insert.setObject(++index, id);
            insert.setString(++index, owner);
            insert.setString(++index, outcome);
            insert.setString(++index, failureCode);
            insert.setString(++index, resultJson);
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not acknowledge command " + id, e);
        }
    }

    /**
     * Deletes commands (and, by cascade, their acknowledgements) older than the retention, a bounded batch.
     *
     * @param retention how long commands are kept
     * @param limit     the most to delete
     * @return how many were deleted
     */
    public int purge(Duration retention, int limit) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement delete = connection.prepareStatement(PURGE_SQL)) {
            delete.setDouble(1, retention.toSeconds());
            delete.setInt(2, limit);
            return delete.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not purge commands", e);
        }
    }

    /**
     * Forgets replicas unseen for longer than the given age.
     *
     * @param age how long unseen
     * @return how many were forgotten
     */
    public int forget(Duration age) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement delete = connection.prepareStatement(FORGET_SQL)) {
            delete.setDouble(1, age.toSeconds());
            return delete.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not forget stale replicas", e);
        }
    }

    private void update(String sql, String owner) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, owner);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not record a heartbeat for " + owner, e);
        }
    }

    private static List<Command> commands(PreparedStatement select) throws SQLException {
        List<Command> commands = new ArrayList<>();
        try (ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                Array targets = rows.getArray("targets");
                commands.add(new Command(rows.getObject("id", UUID.class), rows.getString("control"),
                        rows.getString("arguments"), rows.getString("operation"), rows.getString("submitted_by"),
                        instant(rows.getTimestamp("submitted_at")), instant(rows.getTimestamp("deadline")),
                        Arrays.asList((String[]) targets.getArray()), rows.getBoolean("expired")));
            }
        }
        return commands;
    }

    private static double seconds(Duration duration) {
        return duration.toMillis() / MILLIS_PER_SECOND;
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
