package ru.ludwigandreas.reconciliation.engine.enumeration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.reconciliation.api.EnumerationTask;
import ru.ludwigandreas.reconciliation.api.FeedRequest;
import ru.ludwigandreas.reconciliation.api.PageResult;
import ru.ludwigandreas.reconciliation.audit.ReconciliationAuditEvent;
import ru.ludwigandreas.reconciliation.audit.ReconciliationAuditEvent.Category;
import ru.ludwigandreas.reconciliation.config.EnumerationSettings;
import ru.ludwigandreas.reconciliation.entity.ScopeMemberStatus;
import ru.ludwigandreas.reconciliation.entity.ScopeWalkKind;
import ru.ludwigandreas.reconciliation.entity.SyncScopeMember;
import ru.ludwigandreas.reconciliation.entity.SyncScopeState;
import ru.ludwigandreas.reconciliation.metrics.ReconciliationMetrics;
import ru.ludwigandreas.reconciliation.payload.PayloadCodec;
import ru.ludwigandreas.reconciliation.quota.RateLimitRegistry;
import ru.ludwigandreas.reconciliation.repository.SyncScopeMemberRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Walks one scope: reload it, page through its feed from the checkpoint, apply each page, and on
 * reaching the end complete the walk and sweep.
 *
 * <p>The order of the last two steps is the order of the evidence. Completion advances the scope's
 * generation, which is the statement "a full listing finished"; the sweep then asks which members
 * that listing did not include. A sweep before completion would be reasoning about a walk that had
 * not yet said it was done.
 */
public class ScopeWalker {

    private static final Logger log = LoggerFactory.getLogger(ScopeWalker.class);

    private final ScopeStateService states;
    private final MemberPageApplier applier;
    private final SweepService sweeps;
    private final SyncScopeMemberRepository members;
    private final PayloadCodec payloadCodec;
    private final RateLimitRegistry rateLimits;
    private final ReconciliationMetrics metrics;
    private final AuditSink auditSink;

    /**
     * Creates the walker.
     *
     * @param states       scope state
     * @param applier      applies a page
     * @param sweeps       reports members a full walk did not see
     * @param members      the membership table, for a preview
     * @param payloadCodec payload hashing, for a preview
     * @param rateLimits   partner-scoped request-rate budgets
     * @param metrics      instrumentation
     * @param auditSink    the audit trail
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ScopeWalker(ScopeStateService states,
                       MemberPageApplier applier,
                       SweepService sweeps,
                       SyncScopeMemberRepository members,
                       PayloadCodec payloadCodec,
                       RateLimitRegistry rateLimits,
                       ReconciliationMetrics metrics,
                       AuditSink auditSink) {
        this.states = states;
        this.applier = applier;
        this.sweeps = sweeps;
        this.members = members;
        this.payloadCodec = payloadCodec;
        this.rateLimits = rateLimits;
        this.metrics = metrics;
        this.auditSink = auditSink;
    }

    /**
     * Walks one scope until its feed ends, the run's time runs out, or a page fails.
     *
     * @param <S>         the scope type
     * @param <SK>        the scope key type
     * @param <M>         the item type
     * @param <MK>        the member key type
     * @param enumeration the enumeration
     * @param state       the scope's state as selected for this run
     * @param runId       the run
     * @param deadline    when the run's lease ends
     * @return what the walk did
     */
    public <S, SK, M, MK> WalkReport walk(RegisteredEnumeration<S, SK, M, MK> enumeration,
                                          SyncScopeState state,
                                          UUID runId,
                                          Instant deadline) {
        EnumerationTask<S, SK, M, MK> task = enumeration.task();
        EnumerationSettings settings = enumeration.settings();
        String scopeKey = state.getScopeKey();
        Instant started = Instant.now();

        Optional<S> scope = task.scopes().byKey(task.scopeKeyCodec().decode(scopeKey));
        if (scope.isEmpty()) {
            long forgotten = states.drop(settings.name(), scopeKey);
            log.info("Enumeration '{}' forgot scope {} and its {} member(s): its local row is gone",
                    settings.name(), scopeKey, forgotten);
            audit(settings, "scope.removed", scopeKey, forgotten + " member(s) forgotten", runId);
            return finish(settings, new WalkReport(scopeKey, WalkReport.Status.REMOVED, null,
                    PageOutcome.NONE, null, null), started);
        }

        ScopeStateService.WalkStart start = states.begin(state.getId(),
                WalkPlanner.kindFor(state, settings, started), runId);
        MemberPageApplier.Walk<S> walk = new MemberPageApplier.Walk<>(state.getId(), scopeKey,
                scope.get(), start.stamp(), runId);
        Instant changedSince = start.kind() == ScopeWalkKind.INCREMENTAL ? start.watermark() : null;
        audit(settings, "scope.walk.started", scopeKey, tag(start.kind()), runId);

        PageOutcome total = PageOutcome.NONE;
        String cursor = start.cursor();
        boolean more = true;
        while (more) {
            if (Instant.now().isAfter(deadline)) {
                states.interrupt(state.getId());
                log.info("Enumeration '{}' stopped walking scope {} at the run timeout; the cursor is "
                        + "checkpointed and the next run resumes from it", settings.name(), scopeKey);
                return finish(settings, new WalkReport(scopeKey, WalkReport.Status.INTERRUPTED,
                        start.kind(), total, null, null), started);
            }
            try {
                settings.rateLimitName().ifPresent(limit -> rateLimits.acquire(limit, settings.name()));
                PageResult<M> page = task.feed().fetchPage(scope.get(), cursor,
                        new FeedRequest(settings.pageSize(), changedSince, runId));
                String next = page.hasMore() ? page.nextCursor() : null;
                total = total.plus(applier.applyPage(enumeration, walk, page.items(), next));
                cursor = next;
                more = next != null;
            } catch (RuntimeException failure) {
                // A feed is application code calling a partner: anything can come out of it. The
                // cursor still addresses this page, so the next attempt asks for it again.
                String error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                states.fail(state.getId(), error, settings);
                log.warn("Enumeration '{}' could not walk scope {} past its checkpoint; it backs off "
                        + "and resumes from the same page", settings.name(), scopeKey, failure);
                audit(settings, "scope.walk.failed", scopeKey, error, runId);
                return finish(settings, new WalkReport(scopeKey, WalkReport.Status.FAILED,
                        start.kind(), total, null, error), started);
            }
        }

        long generation = states.complete(state.getId(), settings);
        SweepService.Result sweep = start.kind() == ScopeWalkKind.FULL
                ? sweeps.sweep(enumeration, state.getId(), scopeKey, scope.get(), generation, runId, false)
                : null;
        audit(settings, "scope.walk.completed", scopeKey, tag(start.kind()) + ", " + total, runId);
        return finish(settings, new WalkReport(scopeKey, WalkReport.Status.COMPLETED, start.kind(),
                total, sweep, null), started);
    }

    /**
     * Lists a scope in full and reports what a walk would do, writing nothing.
     *
     * <p>No membership row, no cursor, no call to the reconciler. It does call the partner, because
     * what the partner says is the thing being asked about.
     *
     * @param <S>         the scope type
     * @param <SK>        the scope key type
     * @param <M>         the item type
     * @param <MK>        the member key type
     * @param enumeration the enumeration
     * @param scopeKey    the encoded scope key
     * @param deadline    when to stop listing
     * @return counts of new, changed, unchanged and absent members
     */
    public <S, SK, M, MK> Map<String, Object> preview(RegisteredEnumeration<S, SK, M, MK> enumeration,
                                                      String scopeKey,
                                                      Instant deadline) {
        EnumerationTask<S, SK, M, MK> task = enumeration.task();
        EnumerationSettings settings = enumeration.settings();
        S scope = task.scopes().byKey(task.scopeKeyCodec().decode(scopeKey)).orElseThrow(() ->
                new IllegalArgumentException("Scope '" + scopeKey + "' of enumeration '"
                        + settings.name() + "' has no local row"));

        Map<String, String> known = new HashMap<>();
        for (SyncScopeMember member : members.findAllOfScope(settings.name(), scopeKey)) {
            known.put(member.getMemberKey(),
                    member.getStatus() == ScopeMemberStatus.ACTIVE ? member.getPayloadHash() : null);
        }
        Set<String> listed = new HashSet<>();
        int created = 0;
        int changed = 0;
        int unchanged = 0;
        String cursor = null;
        boolean more = true;
        boolean complete = true;
        UUID previewId = UUID.randomUUID();
        while (more) {
            if (Instant.now().isAfter(deadline)) {
                complete = false;
                break;
            }
            settings.rateLimitName().ifPresent(limit -> rateLimits.acquire(limit, settings.name()));
            PageResult<M> page = task.feed().fetchPage(scope, cursor,
                    new FeedRequest(settings.pageSize(), null, previewId));
            for (M item : page.items()) {
                String memberKey = task.memberKeyCodec().encode(task.memberKey().apply(item));
                if (!listed.add(memberKey)) {
                    continue;
                }
                if (!known.containsKey(memberKey)) {
                    created++;
                } else if (payloadCodec.hash(item).equals(known.get(memberKey))) {
                    unchanged++;
                } else {
                    changed++;
                }
            }
            cursor = page.hasMore() ? page.nextCursor() : null;
            more = cursor != null;
        }
        long absent = known.keySet().stream().filter(memberKey -> !listed.contains(memberKey)).count();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scope", scopeKey);
        report.put("complete", complete);
        report.put("new", created);
        report.put("changed", changed);
        report.put("unchanged", unchanged);
        // Only a complete listing can say a member is absent; a partial one would call everything on
        // the pages it did not reach gone.
        report.put("absent", complete ? absent : null);
        report.put("note", "nothing was applied, recorded or checkpointed");
        return report;
    }

    private WalkReport finish(EnumerationSettings settings, WalkReport report, Instant started) {
        metrics.recordScopeWalk(settings.name(), report.kind() == null ? "none" : tag(report.kind()),
                report.statusTag(), Duration.between(started, Instant.now()));
        return report;
    }

    private void audit(EnumerationSettings settings, String event, String scopeKey, String detail, UUID runId) {
        if (!settings.auditEnabled()) {
            return;
        }
        auditSink.record(ReconciliationAuditEvent.builder(settings.name(), Category.SCOPE, event)
                .subject(scopeKey)
                .detail(detail)
                .runId(runId)
                .build());
    }

    private static String tag(ScopeWalkKind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }
}
