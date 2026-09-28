package ru.ludwigandreas.idempotency.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ludwigandreas.idempotency.api.ClaimMode;
import ru.ludwigandreas.idempotency.api.ClaimOutcome;
import ru.ludwigandreas.idempotency.api.ClaimRequest;
import ru.ludwigandreas.idempotency.api.ClaimResult;
import ru.ludwigandreas.idempotency.api.IdempotencyStore;
import ru.ludwigandreas.idempotency.api.StoredResponse;
import ru.ludwigandreas.idempotency.entity.ClaimState;
import ru.ludwigandreas.idempotency.entity.IdempotencyClaimEntity;
import ru.ludwigandreas.idempotency.metrics.IdempotencyMetrics;
import ru.ludwigandreas.idempotency.repository.IdempotencyClaimRepository;
import ru.ludwigandreas.idempotency.sql.ClaimGateway;

/**
 * The claim table, backed by one conditional upsert.
 *
 * <p>Everything load-bearing is in the statement rather than here - see
 * {@code ru.ludwigandreas.idempotency.sql} for why {@code ON CONFLICT ... DO UPDATE} and not a read
 * followed by an insert, and not {@code DO NOTHING} either.
 *
 * <h2>Behaviour at three replicas</h2>
 *
 * <p>Two replicas handed the same Kafka record concurrently both claim the same scope and key. Whichever
 * statement reaches the row first holds the row lock; the second blocks on it, and when the first commits
 * the second's {@code DO UPDATE} sees the committed row and returns the winner's request id. The loser
 * then answers its caller with the winner's request without writing anything, and no message is sent
 * twice.
 *
 * <h2>The two claim modes are two transaction boundaries</h2>
 *
 * <p>The mode decides which transaction the statement runs in, and nothing else in this class:
 *
 * <ul>
 *   <li>{@link ClaimMode#TRANSACTIONAL} runs in the <em>caller's</em> transaction, the same one that
 *       writes whatever the claim is protecting. That is required rather than convenient: a claim that
 *       committed independently would leave a key permanently reserved for a request whose transaction
 *       then rolled back, and the retry of that request would be rejected as a duplicate of something
 *       that does not exist. A caller with no transaction open is refused at the first claim - the
 *       equivalent of {@code Propagation.MANDATORY} - rather than quietly given an independent commit
 *       that looks like it worked.</li>
 *   <li>{@link ClaimMode#STANDALONE} runs in a new transaction of its own, the equivalent of
 *       {@code Propagation.REQUIRES_NEW}. The claim has to be visible to other replicas before the work
 *       finishes - that is the whole point of an {@code IN_PROGRESS} state - so it cannot wait for a
 *       transaction that may not exist and may span several calls out.</li>
 * </ul>
 *
 * <h2>Why a TransactionTemplate and not {@code @Transactional}</h2>
 *
 * <p>Because the mode is a runtime value and {@code @Transactional} is a compile-time one. Spring applies
 * it with a proxy around calls that arrive from <em>outside</em> the bean, so a {@code claim} method that
 * dispatched to an annotated {@code claimTransactionally} on {@code this} would bypass the proxy and run
 * under no propagation rule at all - silently, and only under concurrency. The two ways out of that trap
 * are a second bean whose only purpose is to be somebody else (which is what
 * {@code notification-service}'s {@code LockLeaseService} was, and what {@code job-core} deleted when it
 * absorbed the lock) or declaring the boundary where the decision is made. This is the second.
 */
@Slf4j
public class PostgresIdempotencyStore implements IdempotencyStore {

    /** The {@code failure_reason} column's length. */
    private static final int MAX_REASON = 512;

    private final ClaimGateway gateway;
    private final IdempotencyClaimRepository repository;
    private final ObjectMapper objectMapper;
    private final IdempotencyMetrics metrics;
    private final Clock clock;

    /** Runs a unit of work in a transaction of its own, whatever the caller was doing. */
    private final TransactionTemplate requiresNew;

    /** Runs a unit of work in the caller's transaction, or its own if there is none. */
    private final TransactionTemplate required;

    /**
     * Creates the store.
     *
     * @param gateway            runs the conditional upsert
     * @param repository         the reads, the state transitions and the purge
     * @param transactionManager the transaction manager the two templates are built over
     * @param objectMapper       serialises a stored response's headers
     * @param metrics            what this module reports about itself
     * @param clock              the clock claims are judged against
     */
    public PostgresIdempotencyStore(ClaimGateway gateway, IdempotencyClaimRepository repository,
                                    PlatformTransactionManager transactionManager,
                                    ObjectMapper objectMapper, IdempotencyMetrics metrics, Clock clock) {
        this.gateway = gateway;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.clock = clock;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.required = new TransactionTemplate(transactionManager);
    }

    @Override
    public ClaimResult claim(ClaimRequest request) {
        Instant now = clock.instant();
        if (request.mode() == ClaimMode.TRANSACTIONAL) {
            requireCallerTransaction(request);
            // No template at all: the statement goes onto the connection already bound to the caller's
            // transaction, and wrapping it in a PROPAGATION_REQUIRED template would only add a synonym
            // for "the transaction that is already open".
            return record(gateway.claim(request, now), request);
        }
        return record(requiresNew.execute(status -> gateway.claim(request, now)), request);
    }

    @Override
    public Optional<ClaimResult> find(String scope, String key) {
        Instant now = clock.instant();
        Optional<ClaimResult> found = required.execute(status ->
                repository.findActive(scope, key, now).map(PostgresIdempotencyStore::asResult));
        // A TransactionTemplate's callback result is nullable by signature, never by this callback.
        return found == null ? Optional.empty() : found;
    }

    @Override
    public boolean complete(String scope, String key, UUID requestId, StoredResponse response) {
        boolean applied = Boolean.TRUE.equals(requiresNew.execute(status -> response == null
                ? repository.complete(scope, key, requestId, null, null, null, null)
                : repository.complete(scope, key, requestId, response.status(), response.contentType(),
                        headersAsJson(response.headers()), response.body())));
        if (!applied) {
            // The claim is no longer this caller's: its lease lapsed and another instance took it over.
            // Logged at warn rather than thrown, because the work itself succeeded - what failed is
            // publishing its response for replay, and the instance that now holds the claim is about to
            // publish its own.
            log.warn("Could not complete claim {}/{} for request {}: it is no longer held by this caller",
                    scope, key, requestId);
        }
        return applied;
    }

    @Override
    public boolean fail(String scope, String key, UUID requestId, String reason) {
        return Boolean.TRUE.equals(requiresNew.execute(status ->
                repository.fail(scope, key, requestId, truncated(reason))));
    }

    @Override
    public boolean renewLease(String scope, String key, UUID requestId, Duration lease) {
        Instant now = clock.instant();
        return Boolean.TRUE.equals(requiresNew.execute(status ->
                repository.renewLease(scope, key, requestId, now.plus(lease), now)));
    }

    @Override
    public long purgeExpired(Instant now) {
        return purgeBatch(now, Integer.MAX_VALUE);
    }

    /**
     * Both modes, because this is the store the conditional upsert was written for.
     *
     * <p>It is the only backend that can serve {@link ClaimMode#TRANSACTIONAL} at all: that mode requires
     * the claim to commit inside the caller's database transaction, which nothing outside the database
     * can do. See {@code RedisIdempotencyStore}.
     */
    @Override
    public boolean supports(ClaimMode mode) {
        return true;
    }

    /**
     * Drops a batch of expired claims and says how many.
     *
     * <p>One transaction per batch, so the purge job can renew its lease between batches and a run that
     * is stopped part way has committed everything it did up to that point.
     *
     * @param now       the instant windows are judged against
     * @param batchSize the most claims to drop in this statement
     * @return how many were dropped
     */
    public long purgeBatch(Instant now, int batchSize) {
        Long purged = requiresNew.execute(status -> repository.purgeExpired(now, batchSize));
        return purged == null ? 0L : purged;
    }

    /**
     * How many claims are past their window right now.
     *
     * @param now the instant windows are judged against
     * @return the count
     */
    public long countExpired(Instant now) {
        Long count = required.execute(status -> repository.countExpired(now));
        return count == null ? 0L : count;
    }

    /**
     * Refuses a transactional claim with no transaction to join.
     *
     * <p>The check {@code Propagation.MANDATORY} would have made, made explicitly. Degrading to an
     * independent commit instead is the failure this mode exists to prevent, and it would only show up
     * as a key that cannot be retried after some unrelated rollback, days later.
     */
    private static void requireCallerTransaction(ClaimRequest request) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException(
                    "A TRANSACTIONAL claim must run inside the caller's transaction, and none is open for "
                            + request.scope() + "/" + request.key() + ". Either open one - the claim has to"
                            + " commit with the work it protects, or a rollback leaves the key reserved for"
                            + " a request that never happened - or use ClaimMode.STANDALONE, which commits"
                            + " independently and reports its outcome afterwards.");
        }
    }

    /** Counts the outcome, and logs the duplicate path at debug. */
    private ClaimResult record(ClaimResult result, ClaimRequest request) {
        metrics.claim(request.scope(), result.outcome().name());
        if (!result.won()) {
            log.debug("Idempotency key {}/{} is held by request {} ({})",
                    request.scope(), request.key(), result.owner(), result.outcome());
        }
        return result;
    }

    /** A stored claim as a result, for the read-only pre-check. */
    private static ClaimResult asResult(IdempotencyClaimEntity claim) {
        ClaimOutcome outcome = claim.getState() == ClaimState.IN_PROGRESS
                ? ClaimOutcome.IN_PROGRESS
                : ClaimOutcome.COMPLETED;
        // The response is deliberately not reconstructed here. find() is a pre-check, and handing back a
        // replayable response from a method whose contract is "a cheap look" is how a caller ends up
        // replaying without ever having claimed - which skips the fingerprint check.
        return new ClaimResult(outcome, claim.getRequestId(), claim.getFingerprint(), null,
                claim.getExpiresAt());
    }

    /**
     * The headers as JSON.
     *
     * <p>A failure here loses the replayed headers and nothing else, which is why it does not propagate:
     * the status and the body are what carry the answer, and failing a completion would leave the claim
     * {@code IN_PROGRESS} for work that actually succeeded.
     */
    private String headersAsJson(Map<String, String> headers) {
        if (headers.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(headers);
        } catch (JsonProcessingException e) {
            log.warn("Could not serialise the response headers of a claim; storing none", e);
            return null;
        }
    }

    /** The failure reason, cut to the column. A reason is for a person reading the table, not a payload. */
    private static String truncated(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= MAX_REASON ? reason : reason.substring(0, MAX_REASON);
    }
}
