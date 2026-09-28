package ru.ludwigandreas.idempotency.sql;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import ru.ludwigandreas.idempotency.api.ClaimMode;
import ru.ludwigandreas.idempotency.api.ClaimOutcome;
import ru.ludwigandreas.idempotency.api.ClaimRequest;
import ru.ludwigandreas.idempotency.api.ClaimResult;
import ru.ludwigandreas.idempotency.api.StoredResponse;
import ru.ludwigandreas.idempotency.entity.ClaimState;

/**
 * Runs {@link ClaimStatements#CLAIM} and turns its one returned row into a {@link ClaimResult}.
 *
 * <h2>Why this is JDBC and why the connection is not its own</h2>
 *
 * <p>Unlike {@code JdbcRunLock}, which deliberately takes a short auto-commit connection of its own so
 * that a lease is visible the instant it is taken, this gateway uses the <em>caller's</em> connection -
 * {@code NamedParameterJdbcTemplate} resolves it through {@code DataSourceUtils}, which returns the
 * connection bound to the current transaction when there is one. That is not an oversight, it is the
 * requirement: a {@link ClaimMode#TRANSACTIONAL} claim must commit with the caller's work, and a claim
 * taken on a separate connection would commit independently - leaving a key permanently reserved for a
 * request whose transaction then rolled back, so that the retry of that request is rejected as a
 * duplicate of something that does not exist.
 *
 * <p>{@link ClaimMode#STANDALONE} gets its independent commit the other way round, from
 * {@code Propagation.REQUIRES_NEW} on the store method, which is where a transaction boundary belongs.
 * One mechanism, two behaviours, decided by the mode rather than by which of two connection strategies
 * a class happened to use.
 */
public class ClaimGateway {

    private static final Logger log = LoggerFactory.getLogger(ClaimGateway.class);

    /** The shape the response headers are stored in. */
    private static final TypeReference<LinkedHashMap<String, String>> HEADER_MAP = new TypeReference<>() { };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * Creates the gateway.
     *
     * @param dataSource   the application's data source; connections are resolved through the current
     *                     transaction, never opened independently
     * @param objectMapper used to read a stored response's headers back
     */
    public ClaimGateway(DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.objectMapper = objectMapper;
    }

    /**
     * Claims the key, or reports what the current holder is doing.
     *
     * @param request the claim
     * @param now     the instant expiry and leases are judged against, passed in rather than read here
     *                so that one claim judges every column against one time - reading the clock twice
     *                inside one decision is how a key is both expired and live in the same statement
     * @return the outcome
     */
    public ClaimResult claim(ClaimRequest request, Instant now) {
        ClaimState state = request.mode() == ClaimMode.TRANSACTIONAL
                ? ClaimState.COMPLETED
                : ClaimState.IN_PROGRESS;
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", UUID.randomUUID());
        parameters.put("scope", request.scope());
        parameters.put("key", request.key());
        parameters.put("requestId", request.requestId());
        parameters.put("state", state.name());
        parameters.put("fingerprint", request.fingerprint());
        parameters.put("leaseExpiresAt", request.mode() == ClaimMode.STANDALONE
                ? Timestamp.from(now.plus(request.lease())) : null);
        parameters.put("now", Timestamp.from(now));
        parameters.put("expiresAt", Timestamp.from(now.plus(request.ttl())));

        ClaimResult result = jdbc.query(ClaimStatements.CLAIM, parameters,
                rows -> rows.next() ? read(rows, request.requestId()) : null);
        if (result == null) {
            // Unreachable: the statement either inserts or updates, and both return a row. Treating it
            // as a win rather than throwing keeps a driver-level surprise from dropping the caller's
            // work entirely - a double execution is recoverable, a silently discarded request is not.
            log.warn("The claim statement returned no row for {}/{}; treating the claim as won",
                    request.scope(), request.key());
            return ClaimResult.claimed(request.requestId(), request.fingerprint(),
                    now.plus(request.ttl()));
        }
        return result;
    }

    /** Reads the one returned row, deciding the outcome from whose request id came back. */
    private ClaimResult read(ResultSet rows, UUID self) throws SQLException {
        UUID owner = rows.getObject("request_id", UUID.class);
        ClaimState state = ClaimState.valueOf(rows.getString("state"));
        Instant expiresAt = instant(rows, "expires_at");
        if (self.equals(owner)) {
            return ClaimResult.claimed(owner, rows.getString("fingerprint"), expiresAt);
        }
        ClaimOutcome outcome = state == ClaimState.IN_PROGRESS
                ? ClaimOutcome.IN_PROGRESS
                : ClaimOutcome.COMPLETED;
        // The lease, not the TTL, is what an in-flight duplicate's Retry-After is derived from: the
        // honest earliest moment to come back is when the holder would have lost the claim had it died.
        Instant retryAt = outcome == ClaimOutcome.IN_PROGRESS
                ? Optional.ofNullable(instant(rows, "lease_expires_at")).orElse(expiresAt)
                : expiresAt;
        return new ClaimResult(outcome, owner, rows.getString("fingerprint"), storedResponse(rows),
                retryAt);
    }

    /** The stored response, or null when the holder stored none. */
    private StoredResponse storedResponse(ResultSet rows) throws SQLException {
        int status = rows.getInt("response_status");
        if (rows.wasNull()) {
            return null;
        }
        return new StoredResponse(status, rows.getString("response_content_type"),
                headers(rows.getString("response_headers")), rows.getBytes("response_body"));
    }

    /**
     * The stored headers.
     *
     * <p>A body that will not parse costs the replay its headers and nothing else. Failing the request
     * instead would turn a corrupt row - which can only have come from this module - into an outage for
     * a caller whose work was done correctly, and the status and the body are the parts that carry the
     * answer.
     */
    private Map<String, String> headers(String stored) {
        if (stored == null || stored.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(stored, HEADER_MAP);
        } catch (IOException e) {
            log.warn("Could not read the stored response headers of a claim; replaying without them", e);
            return Map.of();
        }
    }

    private static Instant instant(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
