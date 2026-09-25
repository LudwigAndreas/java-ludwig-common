package ru.ludwigandreas.export.lifecycle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import ru.ludwigandreas.export.api.ReportFormat;
import ru.ludwigandreas.export.api.ReportRequest;
import ru.ludwigandreas.export.api.SortKey;
import ru.ludwigandreas.export.config.ExportProperties;
import ru.ludwigandreas.export.entity.ExportReportSubscription;
import ru.ludwigandreas.export.entity.ExportSavedReport;
import ru.ludwigandreas.export.registry.ReportWriterFactories;
import ru.ludwigandreas.export.repository.ExportReportSubscriptionRepository;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.job.core.lock.RunLockHandle;
import ru.ludwigandreas.job.core.schedule.ScheduleSpec;
import ru.ludwigandreas.job.core.schedule.SelfSchedulingLifecycle;

/**
 * Turns due subscriptions into report runs.
 *
 * <h2>A missed window is skipped, never replayed</h2>
 *
 * <p>Each tick asks, for every live subscription: has a cron window elapsed since {@code lastRunAt}?
 * If so it submits <em>one</em> run and moves {@code lastRunAt} to now. An instance that was down for
 * a weekend therefore produces one report per subscription on restart, not forty-eight.
 *
 * <p>That is a deliberate loss and it is stated on the entity too. Replaying every missed window
 * turns a routine outage into a storm against the database and every enrichment partner at once, on
 * an instance that has just started and whose caches are cold. A daily report that missed Saturday is
 * a gap somebody can fill by running it; a reporting tier that fell over on Monday morning is not.
 *
 * <h2>Under a leased lock</h2>
 *
 * <p>Every instance runs this scheduler, so without a lock every instance would submit the same run.
 * The idempotency key would catch most of that - the requests are identical - but relying on a unique
 * constraint to absorb N-1 duplicate submissions per tick is a way of not deciding. The lock is
 * leased rather than held, so an instance that dies mid-tick does not stop the next one for good.
 *
 * <h2>The run executes as the subscription's subject, not as the service</h2>
 *
 * <p>{@code run_as} names a person, and the run is scoped by <em>their</em> authorities, re-resolved
 * when it executes like any other. A scheduled report that ran with the service's own entitlements
 * would be the one place in the estate where a file is produced that nobody is authorised to see -
 * and it would keep being produced after they left.
 */
@Slf4j
public class ExportSubscriptionScheduler extends SelfSchedulingLifecycle {

    /** The name this job's log lines carry. */
    public static final String JOB_NAME = "ludwig-export-subscriptions";

    private final ExportReportSubscriptionRepository subscriptions;
    private final SavedReportService savedReports;
    private final ReportRequestService requests;
    private final ReportWriterFactories formats;
    private final RunLock lock;
    private final Clock clock;

    // SUPPRESS CHECKSTYLE ParameterNumber - a Spring bean assembled in one @Bean method, where every
    // argument is named by its own bean; there is no positional call site for the rule to protect.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ExportSubscriptionScheduler(TaskScheduler taskScheduler,
                                       ExportReportSubscriptionRepository subscriptions,
                                       SavedReportService savedReports, ReportRequestService requests,
                                       ReportWriterFactories formats, RunLock lock,
                                       ExportProperties properties, Clock clock) {
        super(JOB_NAME, taskScheduler,
                new ScheduleSpec(properties.getPoller().getInterval(), null,
                        properties.getPoller().getInterval()),
                properties.getPoller().getDrainTimeout());
        this.subscriptions = subscriptions;
        this.savedReports = savedReports;
        this.requests = requests;
        this.formats = formats;
        this.lock = lock;
        this.clock = clock;
    }

    @Override
    protected void runOnce() {
        lock.tryAcquire(JOB_NAME, Duration.ofMinutes(2)).ifPresent(this::submitDueUnder);
    }

    private void submitDueUnder(RunLockHandle handle) {
        try (RunLockHandle held = handle) {
            for (ExportReportSubscription subscription : subscriptions.findByEnabledTrue()) {
                submitIfDue(subscription);
            }
        } catch (Exception e) {
            // Releasing a leased lock is not worth failing the job over; the lease expires anyway.
            log.warn("Could not release the subscription scheduler lock: {}", e.toString());
        }
    }

    /**
     * Submits one subscription's run, if its window has elapsed.
     *
     * <p>Every failure here is per subscription and logged rather than propagated: one saved report
     * that drifted from its definition must not stop every other subscription in the estate from
     * running, and the failure is already loud in the log with the name of the configuration.
     */
    private void submitIfDue(ExportReportSubscription subscription) {
        try {
            if (!isDue(subscription)) {
                return;
            }
            ExportSavedReport saved = savedReports.require(subscription.getSavedReportId());
            var run = requests.submit(toRequest(subscription, saved), subscription.getRunAs());
            subscription.setLastRunAt(clock.instant());
            subscription.setLastRunId(run.getId());
            subscriptions.save(subscription);
            log.info("Subscription {} submitted report run {} for saved report '{}'",
                    subscription.getId(), run.getId(), saved.getName());
        } catch (RuntimeException e) {
            log.error("Subscription {} could not be submitted", subscription.getId(), e);
        }
    }

    /**
     * Whether a cron window has elapsed since this subscription last ran.
     *
     * <p>A subscription that has never run is due immediately rather than at its next cron boundary.
     * The alternative would mean a daily report created at 09:01 produces nothing until tomorrow,
     * which reads as the feature not working.
     */
    private boolean isDue(ExportReportSubscription subscription) {
        Instant last = subscription.getLastRunAt();
        if (last == null) {
            return true;
        }
        ZoneId zone = ZoneId.of(subscription.getTimeZone());
        Instant next = CronExpression.parse(subscription.getCron())
                .next(last.atZone(zone))
                .toInstant();
        return !next.isAfter(clock.instant());
    }

    private ReportRequest toRequest(ExportReportSubscription subscription, ExportSavedReport saved) {
        ReportFormat format = formats.require(subscription.getFormatId());
        return new ReportRequest(saved.getDefinitionKey(), saved.getParameters(), saved.getColumnIds(),
                saved.getFilterExpression(), List.<SortKey>of(), List.of(format), Map.of(),
                Locale.ENGLISH, ZoneId.of(subscription.getTimeZone()), saved.getId(),
                // The idempotency key includes the window, so two ticks inside one window submit the
                // same key and the second is recognised as the first rather than queued behind it.
                subscription.getId() + ":" + windowOf(subscription));
    }

    /** The window a submission belongs to, so a duplicate tick is idempotent. */
    private long windowOf(ExportReportSubscription subscription) {
        Instant last = subscription.getLastRunAt();
        return last == null ? 0 : last.getEpochSecond();
    }
}
