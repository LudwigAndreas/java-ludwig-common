package ru.ludwigandreas.notification.service.announcement;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.audit.Actor;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.notification.repository.AnnouncementEmailRunRepository;
import ru.ludwigandreas.notification.repository.AnnouncementRepository;
import ru.ludwigandreas.notification.repository.entity.AnnouncementEmailRunEntity;
import ru.ludwigandreas.notification.repository.entity.AnnouncementEntity;
import ru.ludwigandreas.notification.repository.entity.AudienceKind;
import ru.ludwigandreas.notification.service.NotificationService;
import ru.ludwigandreas.notification.service.exception.AnnouncementNotFoundException;
import ru.ludwigandreas.notification.service.metrics.NotificationMetrics;
import ru.ludwigandreas.notification.service.model.Audience;
import ru.ludwigandreas.notification.service.model.BroadcastRunView;
import ru.ludwigandreas.notification.service.model.AudienceType;
import ru.ludwigandreas.notification.service.model.CategoryClass;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.model.IngressSource;
import ru.ludwigandreas.notification.service.model.NotificationCommand;
import ru.ludwigandreas.notification.service.model.Priority;
import ru.ludwigandreas.notification.service.model.RecipientRef;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.security.principal.SecurityPrincipals;
import ru.ludwigandreas.webcore.operation.OperationStatus;

/**
 * Sends an announcement by email as well as showing it, by paging its audience and creating ordinary
 * deliveries.
 *
 * <h2>Why this reuses the delivery queue rather than sending anything</h2>
 *
 * <p>Nothing here talks to a mail server. Each batch calls {@link NotificationService#submit}, which
 * produces ordinary {@code notification_delivery} rows - so the existing dispatcher, retry schedule,
 * backoff, cluster-wide rate limit, suppression list, preference evaluation and quiet-hours deferral
 * all apply unchanged, per recipient. <b>A broadcast is not a way to bypass any of them</b>, and the
 * cheapest way to guarantee that is to not have a second send path at all.
 *
 * <h2>Exactly-once comes from atomicity; the dedup key is the backstop</h2>
 *
 * <p>One batch creates its deliveries <em>and</em> advances the cursor in <b>one transaction</b>. So
 * a run interrupted anywhere - a lost lease, a {@code SIGKILL}, a cancellation - leaves the cursor
 * exactly where the last complete batch left it, and resumes with no overlap to reconcile. There is
 * no partially-committed batch to think about, which is why there is no per-recipient progress table.
 *
 * <p>The existing unique {@code dedupKey} (idempotency key + channel + recipient) is the second line:
 * if two replicas ever ran the same batch despite the lock, the loser's inserts simply cannot create
 * a second delivery. The batch's idempotency key is derived from the announcement and the cursor, so
 * it is stable across a resume rather than random - which is what makes that backstop meaningful
 * rather than decorative.
 *
 * <h2>The audience is a snapshot, and the inbox stays live</h2>
 *
 * <p>Who gets emailed is resolved as the run walks, from the audience predicate. Who can <em>see</em>
 * the announcement is resolved on every read. The two are allowed to diverge and that is specified
 * rather than hidden: an email is physically sent at an instant and cannot be unsent, whereas
 * visibility is a question asked again every time.
 *
 * <p>So somebody who gains the role after publication sees the announcement and is not emailed, and
 * somebody who loses it mid-run may or may not be emailed depending on where the walk had reached -
 * stated in the spec, because a run lasting minutes cannot also be instantaneous. Re-checking
 * membership per row would make a long run send to a different audience than the one that was
 * approved, which is worse.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementEmailBroadcastService {

    private final AnnouncementRepository announcements;
    private final AnnouncementEmailRunRepository runs;
    private final NotificationService notificationService;
    private final NotificationProperties properties;
    private final NotificationMetrics metrics;
    private final AuditSink auditSink;

    /**
     * Records that an announcement needs an email fan-out.
     *
     * <p>Called from the publish transaction, so the run and the announcement commit together. It
     * creates a row and nothing else: the announcement is visible as soon as the publish returns,
     * while the emails are somebody else's problem a moment later. Doing the fan-out inline would put
     * a hundred thousand inserts in the request that accepts the announcement - a multi-minute
     * transaction pinning the oldest transaction id on the busiest table in the service.
     */
    @Transactional
    public BroadcastRunView startRun(UUID announcementId) {
        AnnouncementEmailRunEntity run = AnnouncementEmailRunEntity.builder()
                .announcementId(announcementId)
                .status(OperationStatus.PENDING)
                .submittedAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        return toView(runs.save(run));
    }

    /** The run for an announcement, for the status endpoint. */
    @Transactional(readOnly = true)
    public Optional<BroadcastRunView> runFor(UUID announcementId) {
        return runs.runFor(announcementId).map(AnnouncementEmailBroadcastService::toView);
    }

    /**
     * Requests that a run stop. <b>Cooperative</b>: this sets a flag and returns.
     *
     * <p>The loop notices between batches. The status is not changed here, because the run may be
     * mid-batch on another replica and reporting it cancelled before it has stopped would be a lie
     * the envelope then has to retract.
     *
     * <p>Deliveries already created are <b>not</b> recalled. They are committed work; silently
     * dropping them would make the count this run already reported untrue, and they are subject to
     * every per-recipient rule anyway.
     *
     * @return the run as it stands, so a cancel of an already-finished run returns its envelope
     *         rather than an error
     */
    @Transactional
    public BroadcastRunView requestCancellation(UUID announcementId) {
        // Every read first, then the write, then the audit record, and nothing after it. The order
        // is load-bearing rather than tidy: a query issued after the audit event has been persisted
        // triggers an auto-flush, and Hibernate's dirty check then schedules an UPDATE on the audit
        // row - which AuditEventEntity refuses, because it is a SnapshotEntity and immutable once
        // imported. The whole transaction fails at commit, in the audit listener, with nothing about
        // the message naming the cancellation that caused it.
        AnnouncementEmailRunEntity run = runs.runFor(announcementId)
                .orElseThrow(() -> new AnnouncementNotFoundException(announcementId));
        AnnouncementEntity announcement = announcements.findForAdministration(announcementId)
                .orElseThrow(() -> new AnnouncementNotFoundException(announcementId));

        if (run.getFinishedAt() == null) {
            run.setCancelRequested(true);
            run.setUpdatedAt(Instant.now());
            run = runs.save(run);
            auditSink.record(AnnouncementAuditEvent.broadcastCancelled(announcementId,
                    announcement.getCategory(), audienceOf(announcement), currentActor(),
                    run.getDeliveriesCreated()).toAuditEvent());
        }
        return toView(run);
    }

    /** Runs the scheduler should work on, oldest first. */
    @Transactional(readOnly = true)
    public List<BroadcastRunView> claimable(int limit) {
        return runs.findClaimable(limit).stream()
                .map(AnnouncementEmailBroadcastService::toView)
                .toList();
    }

    /**
     * The entity as the layer above sees it.
     *
     * <p>The entity stops at this class. A scheduler holding one would be harmless today and is the
     * first step towards a controller holding one, which the layering rules forbid.
     */
    private static BroadcastRunView toView(AnnouncementEmailRunEntity run) {
        return new BroadcastRunView(run.getId(), run.getStatus(), run.getDeliveriesCreated(),
                run.getAudienceTotal(), run.getSubmittedAt(), run.getStartedAt(),
                run.getFinishedAt(), run.getLastError());
    }

    /**
     * Advances one run by one batch.
     *
     * <p>One transaction, deliberately: the deliveries this batch creates and the cursor that records
     * them commit together. That is the whole of the resumability design - see the class javadoc.
     *
     * @return true when there is more to do, false when the run has reached a terminal state
     */
    @Transactional
    public boolean advanceOneBatch(UUID runId) {
        AnnouncementEmailRunEntity run = runs.findById(runId).orElse(null);
        if (run == null || run.getFinishedAt() != null) {
            return false;
        }
        AnnouncementEntity announcement = announcements
                .findForAdministration(run.getAnnouncementId())
                .orElse(null);
        if (announcement == null) {
            // The announcement was purged under the run. Nothing left to send to anybody, and the
            // cascade will take this row too - finish rather than loop on a missing parent.
            return finish(run, OperationStatus.FAILED, "the announcement no longer exists");
        }

        if (run.isCancelRequested()) {
            log.info("Announcement {} broadcast cancelled after {} deliveries",
                    run.getAnnouncementId(), run.getDeliveriesCreated());
            return finish(run, OperationStatus.CANCELLED, null);
        }

        if (run.getStartedAt() == null) {
            run.setStartedAt(Instant.now());
            run.setStatus(OperationStatus.RUNNING);
        }

        int batchSize = properties.getAnnouncements().getFanOut().getBatchSize();
        List<String> subjects = nextSubjects(announcement, run.getCursorSubject(), batchSize);
        if (subjects.isEmpty()) {
            log.info("Announcement {} broadcast finished after {} deliveries",
                    run.getAnnouncementId(), run.getDeliveriesCreated());
            metrics.recordAnnouncementBroadcast(announcement.getCategory(), "succeeded",
                    run.getDeliveriesCreated());
            return finish(run, OperationStatus.SUCCEEDED, null);
        }

        // Submitted through the ordinary ingress, so every per-recipient rule applies. The
        // idempotency key is derived from the announcement and this batch's starting cursor rather
        // than being random, which is what makes the delivery dedup key stable across a resume.
        notificationService.submit(commandFor(announcement, subjects, run.getCursorSubject()));

        run.setCursorSubject(subjects.get(subjects.size() - 1));
        run.setDeliveriesCreated(run.getDeliveriesCreated() + subjects.size());
        run.setUpdatedAt(Instant.now());
        runs.save(run);
        return true;
    }

    /**
     * One page of the audience, after the cursor.
     *
     * <p>The role branch strips the {@code ROLE_} prefix the announcement stores, because
     * {@code security_user_role} holds the code exactly as the directory names it. This and the
     * visibility predicate are the only two places those vocabularies meet, and both say so.
     */
    private List<String> nextSubjects(AnnouncementEntity announcement, String cursor, int limit) {
        if (announcement.getAudienceKind() == AudienceKind.EVERYONE) {
            return runs.activeSubjectsAfter(cursor, limit);
        }
        return runs.activeSubjectsWithRoleAfter(bareRoleCode(announcement.getAudienceValue()),
                cursor, limit);
    }

    private NotificationCommand commandFor(AnnouncementEntity announcement, List<String> subjects,
                                           String cursor) {
        List<RecipientRef> recipients = new ArrayList<>(subjects.size());
        for (String subject : subjects) {
            recipients.add(RecipientRef.ofUser(subject));
        }
        return new NotificationCommand(
                idempotencyKeyFor(announcement.getId(), cursor),
                announcement.getTemplateKey(),
                announcement.getCategory(),
                CategoryClass.valueOf(announcement.getCategoryClass().name()),
                // BULK, because a broadcast must never jump the queue ahead of a password reset. The
                // priority lanes exist for exactly this: one announcement to the whole estate would
                // otherwise starve the traffic people are actually waiting for.
                Priority.BULK,
                Set.of(ChannelType.EMAIL),
                recipients,
                Map.of(),
                null,
                IngressSource.REST,
                null,
                null);
    }

    /**
     * Stable across a resume, which is the point.
     *
     * <p>Derived from the announcement and the batch's starting cursor, so re-running the same batch
     * produces the same key and therefore the same per-delivery dedup keys - and the unique index
     * refuses the duplicates. A random key would make the backstop decorative.
     */
    private static String idempotencyKeyFor(UUID announcementId, String cursor) {
        return "announcement:" + announcementId + ":" + (cursor == null ? "start" : cursor);
    }

    private boolean finish(AnnouncementEmailRunEntity run, OperationStatus status, String error) {
        run.setStatus(status);
        run.setFinishedAt(Instant.now());
        run.setUpdatedAt(Instant.now());
        run.setLastError(error);
        runs.save(run);
        return false;
    }

    private static String bareRoleCode(String stored) {
        return stored != null && stored.startsWith("ROLE_") ? stored.substring("ROLE_".length())
                : stored;
    }

    private static Audience audienceOf(AnnouncementEntity announcement) {
        return new Audience(AudienceType.valueOf(announcement.getAudienceKind().name()),
                announcement.getAudienceValue());
    }

    private static Actor currentActor() {
        return SecurityPrincipals.current()
                .map(principal -> Actor.of(principal.subject()))
                .orElseGet(Actor::system);
    }
}
