package ru.ludwigandreas.job.core.approval;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import ru.ludwigandreas.security.admin.AdminApproval;
import ru.ludwigandreas.security.admin.AdminApprovalState;
import ru.ludwigandreas.security.admin.AdminApprovalStore;
import ru.ludwigandreas.security.principal.PrincipalType;

/**
 * The administrative surface's approval store, on job-core's {@code job_admin_approval} table.
 *
 * <p>Every transition is one {@code UPDATE ... WHERE id = ? AND state = ?}, so of two approvers racing exactly one
 * moves the row and executes; the other sees false. Plain JDBC, as {@code JdbcRunLock} is: job-core has no other
 * persistence layer to borrow.
 */
public class JdbcAdminApprovalStore implements AdminApprovalStore {

    private static final String COLUMNS = "id, operation, request_method, request_path, request_query,"
            + " request_content_type, request_language, request_reason, request_body, requested_by, requester_type,"
            + " requested_at, expires_at, state, decided_by, decided_at, outcome_status, outcome_content_type,"
            + " outcome_body";

    private static final String INSERT_SQL = "INSERT INTO job_admin_approval (" + COLUMNS + ")"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String FIND_SQL = "SELECT " + COLUMNS + " FROM job_admin_approval WHERE id = ?";

    // The body goes with any terminal state; the decider and outcome are kept unless the caller supplies new ones.
    private static final String TRANSITION_SQL = "UPDATE job_admin_approval SET state = ?,"
            + " decided_by = COALESCE(?, decided_by), decided_at = ?,"
            + " outcome_status = COALESCE(?, outcome_status),"
            + " outcome_content_type = COALESCE(?, outcome_content_type),"
            + " outcome_body = COALESCE(?, outcome_body),"
            + " request_body = CASE WHEN ? THEN NULL ELSE request_body END"
            + " WHERE id = ? AND state = ?";

    private static final String EXPIRE_SQL = "UPDATE job_admin_approval SET state = 'EXPIRED', decided_at = ?,"
            + " request_body = NULL WHERE state = 'PENDING' AND expires_at <= ? RETURNING " + COLUMNS;

    private static final String INTERRUPT_SQL = "UPDATE job_admin_approval SET state = 'INTERRUPTED',"
            + " decided_at = ?, request_body = NULL WHERE state = 'APPROVING' AND decided_at < ? RETURNING "
            + COLUMNS;

    private final DataSource dataSource;

    /**
     * Creates the store.
     *
     * @param dataSource the database holding job-core's schema
     */
    public JdbcAdminApprovalStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void create(AdminApproval approval) {
        AdminApproval.StoredRequest request = approval.request();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement insert = connection.prepareStatement(INSERT_SQL)) {
            int index = 0;
            insert.setObject(++index, approval.id());
            insert.setString(++index, approval.operation());
            insert.setString(++index, request.method());
            insert.setString(++index, request.path());
            insert.setString(++index, request.query());
            insert.setString(++index, request.contentType());
            insert.setString(++index, request.acceptLanguage());
            insert.setString(++index, request.reason());
            insert.setBytes(++index, request.body());
            insert.setString(++index, approval.requestedBy());
            insert.setString(++index, approval.requesterType().name());
            insert.setTimestamp(++index, Timestamp.from(approval.requestedAt()));
            insert.setTimestamp(++index, Timestamp.from(approval.expiresAt()));
            insert.setString(++index, approval.state().name());
            insert.setString(++index, approval.decidedBy());
            insert.setTimestamp(++index, timestamp(approval.decidedAt()));
            insert.setObject(++index, approval.outcome() == null ? null : approval.outcome().status(), Types.INTEGER);
            insert.setString(++index, approval.outcome() == null ? null : approval.outcome().contentType());
            insert.setBytes(++index, approval.outcome() == null ? null : approval.outcome().body());
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not record approval " + approval.id(), e);
        }
    }

    @Override
    public Optional<AdminApproval> find(UUID id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement select = connection.prepareStatement(FIND_SQL)) {
            select.setObject(1, id);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(approval(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read approval " + id, e);
        }
    }

    @Override
    public List<AdminApproval> list(Collection<String> operations, AdminApprovalState state, Instant afterRequestedAt,
                                    UUID afterId, int limit) {
        if (operations.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS)
                .append(" FROM job_admin_approval WHERE operation = ANY (?)");
        if (state != null) {
            sql.append(" AND state = ?");
        }
        if (afterRequestedAt != null) {
            sql.append(" AND (requested_at < ? OR requested_at = ? AND id > ?)");
        }
        sql.append(" ORDER BY requested_at DESC, id LIMIT ?");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement select = connection.prepareStatement(sql.toString())) {
            int index = 0;
            select.setArray(++index, connection.createArrayOf("varchar", operations.toArray()));
            if (state != null) {
                select.setString(++index, state.name());
            }
            if (afterRequestedAt != null) {
                select.setTimestamp(++index, Timestamp.from(afterRequestedAt));
                select.setTimestamp(++index, Timestamp.from(afterRequestedAt));
                select.setObject(++index, afterId);
            }
            select.setInt(++index, limit);
            return read(select);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not list approvals", e);
        }
    }

    @Override
    public boolean transition(UUID id, AdminApprovalState from, AdminApprovalState to, String decidedBy, Instant at,
                              AdminApproval.Outcome outcome) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement update = connection.prepareStatement(TRANSITION_SQL)) {
            int index = 0;
            update.setString(++index, to.name());
            update.setString(++index, decidedBy);
            update.setTimestamp(++index, Timestamp.from(at));
            update.setObject(++index, outcome == null ? null : outcome.status(), Types.INTEGER);
            update.setString(++index, outcome == null ? null : outcome.contentType());
            update.setBytes(++index, outcome == null ? null : outcome.body());
            update.setBoolean(++index, to.isTerminal());
            update.setObject(++index, id);
            update.setString(++index, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not move approval " + id + " from " + from + " to " + to, e);
        }
    }

    @Override
    public List<AdminApproval> expire(Instant now) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement update = connection.prepareStatement(EXPIRE_SQL)) {
            update.setTimestamp(1, Timestamp.from(now));
            update.setTimestamp(2, Timestamp.from(now));
            return read(update);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not expire approvals", e);
        }
    }

    @Override
    public List<AdminApproval> interrupt(Instant approvedBefore) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement update = connection.prepareStatement(INTERRUPT_SQL)) {
            update.setTimestamp(1, Timestamp.from(Instant.now()));
            update.setTimestamp(2, Timestamp.from(approvedBefore));
            return read(update);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not mark interrupted approvals", e);
        }
    }

    private static List<AdminApproval> read(PreparedStatement statement) throws SQLException {
        List<AdminApproval> approvals = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                approvals.add(approval(rows));
            }
        }
        return approvals;
    }

    private static AdminApproval approval(ResultSet rows) throws SQLException {
        Integer outcomeStatus = (Integer) rows.getObject("outcome_status");
        return new AdminApproval(rows.getObject("id", UUID.class), rows.getString("operation"),
                new AdminApproval.StoredRequest(rows.getString("request_method"), rows.getString("request_path"),
                        rows.getString("request_query"), rows.getString("request_content_type"),
                        rows.getString("request_language"), rows.getString("request_reason"),
                        rows.getBytes("request_body")),
                rows.getString("requested_by"), PrincipalType.valueOf(rows.getString("requester_type")),
                instant(rows.getTimestamp("requested_at")), instant(rows.getTimestamp("expires_at")),
                AdminApprovalState.valueOf(rows.getString("state")), rows.getString("decided_by"),
                instant(rows.getTimestamp("decided_at")),
                outcomeStatus == null ? null : new AdminApproval.Outcome(outcomeStatus,
                        rows.getString("outcome_content_type"), rows.getBytes("outcome_body")));
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
