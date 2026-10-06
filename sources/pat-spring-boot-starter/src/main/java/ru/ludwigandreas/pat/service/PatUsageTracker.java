package ru.ludwigandreas.pat.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.pat.audit.PatAuditEvents;
import ru.ludwigandreas.pat.config.PatProperties;
import ru.ludwigandreas.pat.entity.PatEntity;
import ru.ludwigandreas.pat.metrics.PatMetrics;
import ru.ludwigandreas.pat.repository.PatRepository;

/**
 * Records that a token was used, splitting the rare security signals from the high-volume timestamp.
 *
 * <p>This class exists because "audit every use" and "never audit a use" are both wrong, and the shape of
 * the right answer is not obvious.
 *
 * <h2>Two paths, and the split is the whole design</h2>
 *
 * <p><b>The three security signals go to the audit sink synchronously, and are not swallowed.</b> First use,
 * use after dormancy, and use from an unseen source are the three that actually indicate compromise, and
 * each is rare by construction - first use happens once in a token's life, and the other two are
 * exceptional. Keeping them synchronous means {@code AuditFailurePolicy} still governs them: if a deployment
 * has configured a sink failure to fail the caller, that decision applies, and a {@code try}/{@code catch}
 * here would override it - which this repository forbids for exactly that reason.
 *
 * <p><b>The {@code last_used_at} write is asynchronous, debounced and best-effort.</b> That one is
 * per-request. Writing it inline would put a row update on the hot path of the system's busiest endpoint and
 * make one hot row a contention point under load; failing the exchange when it fails would mean a telemetry
 * write can break a production pipeline. So it is swallowed - and metered, because a swallowed failure with
 * no counter is a failure nobody ever learns about.
 *
 * <p>Note what that split buys: the volume is on the path that may be lost, and the signal is on the path
 * that may not. Had both been asynchronous and best-effort, {@code AuditFailurePolicy} would have become
 * decorative for these events. Had both been synchronous, the exchange would carry a write per request.
 *
 * <h2>Why detection only happens on a cache miss, and what that costs</h2>
 *
 * <p>Signals are computed from the row's previous {@code lastUsedAt} and {@code lastUsedIp}, which means the
 * row has to have been read. On a verification cache <em>hit</em> there is no row - that is the point of the
 * cache - and reading one to detect a signal would defeat it on the endpoint the cache exists to protect.
 *
 * <p>So this is called only when verification actually touched the database. The consequences, stated
 * plainly rather than discovered later:
 *
 * <ul>
 *   <li><b>First use is always detected.</b> Nothing can be cached before the first verification, so a
 *       token's first use is necessarily a miss.</li>
 *   <li><b>Dormant wake is reliably detected.</b> A token that has been quiet for days has no cache entry
 *       left - the TTL is seconds.</li>
 *   <li><b>An unseen source can be missed</b> if the source changes while a cache entry is still live, which
 *       is a window of one cache TTL. A credential being used from a second address within thirty seconds of
 *       a first is a shape worth knowing about and this will sometimes not see it. Closing that would mean a
 *       database read per request, which is the cost the cache exists to avoid - and the signal survives
 *       anyway for any source that persists beyond one TTL, which a compromised credential's will.</li>
 * </ul>
 */
@Slf4j
public class PatUsageTracker {

    private final PatRepository repository;

    private final PatProperties properties;

    private final AuditSink auditSink;

    private final PatMetrics metrics;

    private final Clock clock;

    /**
     * Where the timestamp write runs.
     *
     * <p>Injected rather than created, so a deployment shares its own executor and a test can pass a
     * same-thread one. A class that creates its own thread pool is a class whose concurrency a deployment
     * cannot tune and a test cannot wait for.
     */
    private final Executor executor;

    // SUPPRESS CHECKSTYLE ParameterNumber - six collaborators, each a distinct seam this class needs:
    // storage, configuration, the audit sink, metrics, the clock and the executor. Grouping any of them
    // into a holder would hide which of them a test has to substitute.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public PatUsageTracker(PatRepository repository, PatProperties properties, AuditSink auditSink,
                           PatMetrics metrics, Clock clock, Executor executor) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * Records a verified use of a token whose row was just read.
     *
     * <p>Called only on a verification cache miss - see the class javadoc for what that costs. Returns
     * immediately: the signals are recorded on the calling thread because they are rare, and the timestamp
     * is handed to the executor because it is not.
     *
     * @param entity    the row verification just read, with its <em>previous</em> use still on it
     * @param sourceIp  where the request came from, for the unseen-source signal
     * @param audience  which service the assertion was minted for, for the first-use record
     */
    public void recordVerifiedUse(PatEntity entity, String sourceIp, String audience) {
        Instant now = clock.instant();

        // Read before the write is scheduled: the async update overwrites exactly the fields the signals
        // are computed from, so computing them afterwards would compare the new state against itself and
        // never fire.
        Instant previousUse = entity.getLastUsedAt();
        String previousIp = entity.getLastUsedIp();
        String patId = entity.getId().toString();
        String owner = entity.getOwnerSubject();

        // No try/catch. Whether a sink failure fails the caller is AuditFailurePolicy's decision, resolved
        // from configuration, and a catch in a library overrides a decision the deployment made.
        if (previousUse == null) {
            auditSink.record(new PatAuditEvents.FirstUse(patId, owner, sourceIp, audience).toAuditEvent());
        } else {
            if (isDormant(previousUse, now)) {
                auditSink.record(
                        new PatAuditEvents.DormantWake(patId, owner, sourceIp, previousUse).toAuditEvent());
            }
            if (isUnseenSource(previousIp, sourceIp)) {
                auditSink.record(
                        new PatAuditEvents.UnseenSource(patId, owner, sourceIp, previousIp).toAuditEvent());
            }
        }

        if (shouldWrite(previousUse, previousIp, sourceIp, now)) {
            scheduleLastUsedWrite(entity.getId(), sourceIp, now);
        }
    }

    /**
     * Whether the row's last-use fields are stale enough to be worth a write.
     *
     * <p>The debounce. A token exchanged a thousand times in five minutes produces one write rather than a
     * thousand, which is the difference between a hot row and a contention point.
     *
     * <p>A <b>changed source address always writes</b>, regardless of the debounce. Otherwise the stored
     * {@code lastUsedIp} would lag by up to the debounce interval, and the unseen-source signal computed
     * from it on a later request would compare against an address that is no longer the most recent one -
     * which produces a spurious signal for a caller alternating between two addresses, and a missed one for
     * a sequence of three.
     */
    private boolean shouldWrite(Instant previousUse, String previousIp, String sourceIp, Instant now) {
        if (previousUse == null) {
            return true;
        }
        if (isUnseenSource(previousIp, sourceIp)) {
            return true;
        }
        Duration debounce = properties.getLastUsedDebounce();
        if (debounce == null || debounce.isZero() || debounce.isNegative()) {
            return true;
        }
        return !previousUse.plus(debounce).isAfter(now);
    }

    /**
     * Writes the timestamp, asynchronously and best-effort.
     *
     * <p>Re-reads the row inside the task rather than mutating the entity the caller handed over, which
     * matters for two reasons: the caller's entity belongs to a transaction that has very likely committed
     * and closed by the time this runs, and writing through a detached instance would either throw or
     * silently overwrite a concurrent change to a field this class has no business touching.
     *
     * <p>The {@code catch} is deliberate and is the one in this class. It covers a <b>telemetry write</b>,
     * not an audit record: losing a {@code last_used_at} update costs an operator precision in a dormancy
     * report, and failing an exchange because of it costs a production pipeline. Metered so that the
     * swallowing is itself visible.
     */
    private void scheduleLastUsedWrite(UUID patId, String sourceIp, Instant now) {
        executor.execute(() -> {
            try {
                repository.findById(patId).ifPresent(row -> {
                    row.setLastUsedAt(now);
                    row.setLastUsedIp(sourceIp);
                    row.setUseCount(row.getUseCount() + 1);
                    repository.save(row);
                });
            } catch (RuntimeException failure) {
                // CHECKSTYLE.OFF: IllegalCatch - a best-effort telemetry write must not propagate. Any
                // runtime failure here - a closed connection, a lock timeout, an optimistic-locking
                // conflict with a concurrent rotation - has to end in a counter rather than in a failed
                // exchange, and enumerating the ones a driver might throw would be a list that falls behind
                // the driver.
                metrics.recordLastUsedWriteFailed();
                log.warn("Could not record last use of personal access token {} - the exchange succeeded"
                        + " and this is best-effort, but a dormancy report over this token will be"
                        + " imprecise: {}", patId, failure.toString());
                // CHECKSTYLE.ON: IllegalCatch
            }
        });
    }

    private boolean isDormant(Instant previousUse, Instant now) {
        Duration dormancy = properties.getDormancyThreshold();
        return dormancy != null
                && !dormancy.isZero()
                && !dormancy.isNegative()
                && previousUse.plus(dormancy).isBefore(now);
    }

    /**
     * Whether this source has not been seen for this token.
     *
     * <p>A null previous address is deliberately <b>not</b> an unseen source: it means the row predates
     * last-use tracking or its write was lost, and treating "we do not know" as "it changed" would turn
     * every lost telemetry write into a security signal. A signal that fires on its own infrastructure's
     * hiccups is a signal that gets muted.
     */
    private boolean isUnseenSource(String previousIp, String sourceIp) {
        return previousIp != null && sourceIp != null && !previousIp.equals(sourceIp);
    }
}
