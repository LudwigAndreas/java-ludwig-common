package ru.ludwigandreas.usersettings.backfill;

import java.util.UUID;
import java.util.function.BiFunction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.usersettings.event.SettingsEventPublisher;
import ru.ludwigandreas.usersettings.exception.SettingConfigurationException;
import ru.ludwigandreas.usersettings.metrics.SettingsMetrics;

/**
 * Republishes the owner's current state as change events, so a projection can be seeded or rebuilt.
 *
 * <h2>What this is for</h2>
 *
 * <p>Three situations, all of which otherwise end with somebody copying a table by hand:
 *
 * <ul>
 *   <li><b>A new consumer.</b> A service starts reading a setting that has existed for years.
 *       Declaring it costs nothing - the definition comes from the shared jar and a setting is a row,
 *       not a column, so there is no migration - but the new replica starts empty, and the topic only
 *       ever carried <em>changes</em>. Every user who set the value and never touched it again is
 *       invisible to it, and resolves to the definition default instead.</li>
 *   <li><b>A rebuilt replica.</b> A projection's tables were dropped, restored from an old backup, or
 *       lost to a bad deploy. Replaying the topic recovers only as far back as the broker's retention
 *       reaches.</li>
 *   <li><b>A gap.</b> A consumer was down longer than the topic's retention window, so the events it
 *       missed are simply gone.</li>
 * </ul>
 *
 * <p>Log compaction is the obvious alternative and does not work here, which is worth stating so that
 * nobody spends an afternoon discovering it: the settings stream is partitioned on the <em>subject</em>
 * (see {@code OutboxSettingsEventPublisher}), not on the subject and the setting key together, so a
 * compacted topic would retain each subject's most recently changed setting and discard the rest.
 * Keying per setting instead would make compaction work and would give up the per-subject ordering
 * the projection relies on.
 *
 * <h2>Why it is safe to run at any time</h2>
 *
 * <p>Every republished event carries the owner's original timestamp. A projection compares that
 * against what it already holds and drops anything that is not newer - so running this against a
 * replica that is already current publishes a great deal of traffic and changes nothing. There is no
 * "is it safe yet" question to answer before running it, which is the property that makes it usable
 * at three in the morning.
 *
 * <p>It is also, for the same reason, resumable by simply running it again: a run that is interrupted
 * leaves valid events behind and a re-run starts from the beginning.
 *
 * <h2>What it does not do</h2>
 *
 * <p><b>No access check.</b> This is an operator entry point - a job, a runbook step, an admin
 * endpoint - and its tenant may legitimately be null, which is not a subject the access policy can
 * even be asked about. {@code SettingsBackfillController} is the guarded path: it pins the caller's
 * own tenant and requires the administrative authority before it ever reaches this class. Anything
 * else calling in is trusted code, on the same footing as {@code SettingsRetentionService}.
 *
 * <p><b>No audit rows.</b> A backfill changes nothing. Writing a change-trail entry per republished
 * row would add millions of entries recording that nothing happened, on the same table the retention
 * job already exists to keep down.
 *
 * <p><b>No redaction, and none possible.</b> A republished event carries the value, PII-flagged or
 * not, because replicating the data is the entire point; this is the same payload the original change
 * event carried. A deployment that does not want a setting's values leaving the owner should not
 * project them at all - see {@code UserSettingChangedEvent}.
 */
@Slf4j
@RequiredArgsConstructor
public class SettingsBackfillService {

    private final SettingsBackfillBatchPublisher batches;
    private final SettingsEventPublisher events;
    private final SettingsMetrics metrics;

    /**
     * Runs a backfill to completion, or until the request's row budget is spent.
     *
     * @throws SettingConfigurationException when nothing is listening - see
     *                                       {@link SettingsEventPublisher#publishes()}. Failing loudly
     *                                       rather than reporting a successful run that published into
     *                                       a no-op publisher, which is the shape of incident where an
     *                                       operator believes a replica has been seeded and it has not
     */
    public SettingsBackfillResult backfill(SettingsBackfillRequest request) {
        if (!events.publishes()) {
            throw new SettingConfigurationException(
                    "Cannot back fill: no settings event publisher is active. The service is either not"
                            + " in owner mode, has ludwig.user-settings.owner.publish-events=false, or has"
                            + " no outbox on the classpath - so the republished events would go nowhere.");
        }

        log.info("Starting settings backfill (tenant={}, settings={}, consents={}, batchSize={}, maxRows={})",
                request.tenantId() == null ? "<all>" : request.tenantId(),
                request.includeSettings() ? describe(request.settingKeys().size()) : "none",
                request.includeConsents() ? describe(request.consentKeys().size()) : "none",
                request.batchSize(), request.maxRows() == 0 ? "unlimited" : request.maxRows());

        Budget budget = new Budget(request.maxRows());
        long settingRows = request.includeSettings() ? walk(request, budget, batches::publishSettings) : 0;
        long consentRows = request.includeConsents() ? walk(request, budget, batches::publishConsents) : 0;

        SettingsBackfillResult result =
                new SettingsBackfillResult(settingRows, consentRows, budget.batches, !budget.exhausted);
        if (settingRows > 0) {
            metrics.recordBackfillPublished("setting", settingRows);
        }
        if (consentRows > 0) {
            metrics.recordBackfillPublished("consent", consentRows);
        }
        log.info("Settings backfill published {} setting rows and {} consent rows in {} batches ({})",
                result.settingRows(), result.consentRows(), result.batches(),
                result.complete() ? "complete" : "row budget reached, run again to continue");
        return result;
    }

    /**
     * Drives one table's keyset scan until it runs dry or the budget is spent.
     *
     * <p>The loop stops on a short batch rather than on an empty one, which saves a final query that
     * would otherwise be issued on every run just to be told there is nothing left.
     */
    private long walk(SettingsBackfillRequest request, Budget budget,
                      BiFunction<SettingsBackfillRequest, UUID, SettingsBackfillBatch> page) {
        long published = 0;
        UUID cursor = null;
        while (!budget.exhausted) {
            SettingsBackfillBatch batch = page.apply(request, cursor);
            budget.batches++;
            published += batch.rowCount();
            budget.spend(batch.rowCount());
            if (batch.rowCount() < request.batchSize()) {
                return published;
            }
            cursor = batch.lastId();
        }
        return published;
    }

    /** Setting keys are declared constants, so counting them is safe to log; the values are not. */
    private static String describe(int keyCount) {
        return keyCount == 0 ? "all" : keyCount + " key(s)";
    }

    /**
     * The run's row allowance, shared by both scans.
     *
     * <p>Mutable rather than a record, because it is threaded through two scans that have to draw on
     * one allowance and a value type would mean rebuilding and rethreading it per batch. It also
     * carries the batch count, so "how many transactions did this take" comes out of the same object
     * that decides when to stop.
     */
    private static final class Budget {

        private final long maxRows;
        private long spent;
        private int batches;
        private boolean exhausted;

        private Budget(long maxRows) {
            this.maxRows = maxRows;
        }

        private void spend(int rows) {
            spent += rows;
            if (maxRows > 0 && spent >= maxRows) {
                exhausted = true;
            }
        }
    }
}
