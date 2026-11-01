package ru.ludwigandreas.identity.projection;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ludwigandreas.cache.api.LudwigCache;
import ru.ludwigandreas.identity.feed.IdentitySweep;
import ru.ludwigandreas.identity.feed.IdentitySweep.SweepResult;
import ru.ludwigandreas.security.authz.Authorities;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.security.principal.PrincipalDisabledEvent;

/**
 * Sweeps over {@code identity_sweep_listed}: the listed subjects are staged as the listing runs, and completion
 * disables the source's unlisted active users in one statement, guarded by the disable ratio.
 *
 * <p>Plain SQL rather than JPA, because both halves are set operations over a whole source: a batch insert of listed
 * subjects and an {@code UPDATE ... WHERE NOT EXISTS ... RETURNING id}. Loading the source's users as entities to
 * compare them in memory is exactly the cost the staging table exists to avoid.
 */
@Slf4j
public class SweepStore {

    /** A sweep's rows older than this were left by a replica that died mid-listing. */
    private static final Duration ORPHAN_AGE = Duration.ofDays(1);

    private static final String COUNT_ACTIVE =
            "SELECT count(*) FROM security_user WHERE source_system = :source AND status = 'ACTIVE'";

    private static final String UNLISTED = " FROM security_user u WHERE u.source_system = :source"
            + " AND u.status = 'ACTIVE' AND NOT EXISTS (SELECT 1 FROM identity_sweep_listed l"
            + " WHERE l.sweep_id = :sweep AND l.subject = u.id)";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final LudwigCache<PrincipalRef, Authorities> authorityCache;
    private final ApplicationEventPublisher events;
    private final IdentityMetrics metrics;
    private final double maxDisableRatio;

    /**
     * Creates the store.
     *
     * @param jdbc            the projection's database
     * @param transactions    a required-propagation transaction template
     * @param authorityCache  evicted for each disabled user after commit
     * @param events          announces each disabled user
     * @param metrics         counts sweeps
     * @param maxDisableRatio {@code ludwig.identity.sweep.max-disable-ratio}
     */
    public SweepStore(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions,
                      LudwigCache<PrincipalRef, Authorities> authorityCache, ApplicationEventPublisher events,
                      IdentityMetrics metrics, double maxDisableRatio) {
        if (maxDisableRatio < 0 || maxDisableRatio > 1) {
            throw new IllegalArgumentException("ludwig.identity.sweep.max-disable-ratio must be between 0 and 1");
        }
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.authorityCache = authorityCache;
        this.events = events;
        this.metrics = metrics;
        this.maxDisableRatio = maxDisableRatio;
    }

    IdentitySweep begin(String source) {
        int orphans = jdbc.update("DELETE FROM identity_sweep_listed WHERE listed_at < :before",
                Map.of("before", Timestamp.from(Instant.now().minus(ORPHAN_AGE))));
        if (orphans > 0) {
            log.info("Deleted {} staged subjects of identity sweeps that never completed", orphans);
        }
        return new Sweep(source, UUID.randomUUID());
    }

    private final class Sweep implements IdentitySweep {

        private final String source;
        private final UUID id;

        private Sweep(String source, UUID id) {
            this.source = source;
            this.id = id;
        }

        @Override
        public void listed(Collection<String> subjects) {
            List<SqlParameterSource> rows = subjects.stream()
                    .filter(subject -> subject != null && !subject.isBlank())
                    .distinct()
                    .<SqlParameterSource>map(subject -> new MapSqlParameterSource()
                            .addValue("sweep", id).addValue("subject", subject))
                    .toList();
            if (!rows.isEmpty()) {
                jdbc.batchUpdate("INSERT INTO identity_sweep_listed (sweep_id, subject) VALUES (:sweep, :subject)"
                        + " ON CONFLICT DO NOTHING", rows.toArray(SqlParameterSource[]::new));
            }
        }

        @Override
        public SweepResult complete() {
            SweepResult result = transactions.execute(status -> {
                MapSqlParameterSource params = new MapSqlParameterSource().addValue("source", source)
                        .addValue("sweep", id);
                long active = count(COUNT_ACTIVE, params);
                long unlisted = count("SELECT count(*)" + UNLISTED, params);
                if (active > 0 && unlisted > maxDisableRatio * active) {
                    log.error("Aborted the sweep of identity source {}: it would disable {} of {} active users,"
                                    + " more than ludwig.identity.sweep.max-disable-ratio={}. No user was changed.",
                            source, unlisted, active, maxDisableRatio);
                    deleteRows();
                    return new SweepResult(source, true, active, unlisted, 0);
                }
                // source_timestamp is cleared: the sweep's verdict rests on absence, not on a change at the source,
                // so the next upsert for the user - however old its occurredAt - must be able to restore them.
                List<String> disabled = jdbc.queryForList("UPDATE security_user SET status = 'DISABLED',"
                        + " source_timestamp = NULL"
                        + " WHERE id IN (SELECT u.id" + UNLISTED + ") RETURNING id", params, String.class);
                for (String subject : disabled) {
                    authorityCache.evictAfterCommit(PrincipalRef.user(subject));
                    events.publishEvent(new PrincipalDisabledEvent(subject, "sweep"));
                }
                deleteRows();
                if (!disabled.isEmpty()) {
                    log.info("The sweep of identity source {} disabled {} users it no longer lists", source,
                            disabled.size());
                }
                return new SweepResult(source, false, active, unlisted, disabled.size());
            });
            metrics.sweep(source, result.aborted(), result.disabled());
            return result;
        }

        @Override
        public void abandon() {
            deleteRows();
        }

        private void deleteRows() {
            jdbc.update("DELETE FROM identity_sweep_listed WHERE sweep_id = :sweep", Map.of("sweep", id));
        }

        private long count(String sql, MapSqlParameterSource params) {
            Long value = jdbc.queryForObject(sql, params, Long.class);
            return value == null ? 0 : value;
        }
    }
}
