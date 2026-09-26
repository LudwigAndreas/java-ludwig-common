package ru.ludwigandreas.usersettings.retention;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.usersettings.repository.UserConsentRepository;
import ru.ludwigandreas.usersettings.repository.UserSettingValueRepository;

/**
 * Keeps the tables that grow without bound from growing without bound.
 *
 * <h2>What is purged, and what is not</h2>
 *
 * <p><b>Audit rows are no longer purged here.</b> The change trail moved into {@code audit_event} and is
 * purged by {@code audit-spring-boot-starter}'s own per-category retention job, under
 * {@code ludwig.audit.retention}. Two schedules deleting from one table would be two retention policies
 * for one set of rows, with the shorter one silently winning - and the shorter one was this module's,
 * whose default was a year against the platform's seven. A deployment that wants the old figure sets
 * {@code ludwig.audit.retention.by-category.settings}. {@code ludwig.user-settings.retention.audit} is
 * no longer read; the module README says so.
 *
 * <p><b>Tombstones</b> are purged on the same schedule, with a much shorter retention. A tombstone
 * only has to outlive the window in which a late event could still arrive for the setting it covers,
 * which is bounded by the broker's own retention rather than by anything this module does - so the
 * default is thirty days, comfortably longer than a typical topic retention and short enough that
 * resetting a setting does not leave a permanent row.
 *
 * <p><b>Consents are not purged on a schedule, at all.</b> {@link #purgeConsents(Duration, int)}
 * exists and works, and nothing in this module ever calls it. How long consent evidence has to be
 * kept is a legal question with a different answer per jurisdiction and per consent, and a default
 * that quietly deleted it on a timer would be destroying exactly the evidence the ledger was
 * designed to preserve. An operator acting on a written retention policy calls this; a scheduler
 * does not.
 *
 * <h2>Why it runs in batches</h2>
 *
 * <p>A single unbounded delete over a year of accumulated rows takes a long lock and produces a
 * large amount of WAL, on tables that are also being written to. Each call removes at most one batch
 * and reports whether there is more, so the scheduler simply comes back - the purge takes longer in
 * wall-clock time and never blocks a user's write.
 */
@Slf4j
@RequiredArgsConstructor
public class SettingsRetentionService {

    private final UserSettingValueRepository values;
    private final UserConsentRepository consents;
    private final Clock clock;

    /**
     * Removes one batch of tombstones whose late-event window has passed.
     *
     * @return how many tombstones were removed; equal to {@code batchSize} means there is more to do
     */
    @Transactional
    public int purgeTombstones(Duration retention, int batchSize) {
        Instant cutoff = cutoff(retention);
        int removed = values.purgeTombstonesOlderThan(cutoff, batchSize);
        if (removed > 0) {
            log.info("Purged {} settings tombstones removed before {}", removed, cutoff);
        }
        return removed;
    }

    /**
     * Deletes consent rows older than {@code retention}. Never called by this module.
     *
     * <p>Logged at {@code warn} even on success, because a successful consent deletion is a fact
     * somebody should see in the log without having gone looking for it.
     */
    @Transactional
    public int purgeConsents(Duration retention, int batchSize) {
        Instant cutoff = cutoff(retention);
        int removed = consents.purgeOlderThan(cutoff, batchSize);
        if (removed > 0) {
            log.warn("Purged {} consent records with decisions older than {}, under an explicit"
                    + " retention policy", removed, cutoff);
        }
        return removed;
    }

    private Instant cutoff(Duration retention) {
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException(
                    "A retention period must be positive; a zero or negative one would delete everything,"
                            + " including rows written moments ago");
        }
        return clock.instant().minus(retention);
    }
}
