package ru.ludwigandreas.reconciliation.engine.enumeration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.job.core.backoff.BackoffCalculator;
import ru.ludwigandreas.reconciliation.api.EnumerationTask;
import ru.ludwigandreas.reconciliation.api.LeftResult;
import ru.ludwigandreas.reconciliation.api.MemberContext;
import ru.ludwigandreas.reconciliation.api.MemberResult;
import ru.ludwigandreas.reconciliation.audit.ReconciliationAuditEvent;
import ru.ludwigandreas.reconciliation.audit.ReconciliationAuditEvent.Category;
import ru.ludwigandreas.reconciliation.config.EnumerationSettings;
import ru.ludwigandreas.reconciliation.entity.ScopeMemberStatus;
import ru.ludwigandreas.reconciliation.entity.SyncScopeMember;
import ru.ludwigandreas.reconciliation.metrics.ReconciliationMetrics;
import ru.ludwigandreas.reconciliation.payload.PayloadCodec;
import ru.ludwigandreas.reconciliation.repository.SyncScopeMemberRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Applies one page of a scope's members.
 *
 * <h2>A page is the transaction; an item is the fallback</h2>
 *
 * <p>The whole page is applied in one transaction: the membership lookup, the reconciler's writes,
 * the membership rows and the cursor. That is what makes the cursor a fact - it commits with the page
 * it describes or not at all - and it keeps the common, all-good page at one commit instead of one
 * per item.
 *
 * <p>When that transaction fails, the page is replayed item by item, each in its own transaction.
 * Items that apply are recorded; an item that throws is recorded as failed on its own membership row,
 * with a backoff and, at the task's budget, a quarantine. The walk goes on to the next page either
 * way, so one bad member costs its scope one member rather than the scope.
 *
 * <h2>Why there is no staged record</h2>
 *
 * <p>The staging inbox exists so that a retry does not call the partner again for a key. An
 * enumeration lists its members again on the next walk whether anything failed or not, so the next
 * walk is the retry, and a row per member per walk would buy nothing.
 */
public class MemberPageApplier {

    private static final Logger log = LoggerFactory.getLogger(MemberPageApplier.class);

    private final SyncScopeMemberRepository members;
    private final ScopeStateService states;
    private final PayloadCodec payloadCodec;
    private final ReconciliationMetrics metrics;
    private final AuditSink auditSink;
    private final TransactionTemplate transactions;

    /**
     * Creates the applier.
     *
     * @param members            the membership table
     * @param states             scope state, for the cursor
     * @param payloadCodec       payload hashing
     * @param metrics            instrumentation
     * @param auditSink          the audit trail
     * @param transactionManager the transaction manager
     */
    public MemberPageApplier(SyncScopeMemberRepository members,
                             ScopeStateService states,
                             PayloadCodec payloadCodec,
                             ReconciliationMetrics metrics,
                             AuditSink auditSink,
                             PlatformTransactionManager transactionManager) {
        this.members = members;
        this.states = states;
        this.payloadCodec = payloadCodec;
        this.metrics = metrics;
        this.auditSink = auditSink;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * One scope's walk, as far as applying a page needs to know it.
     *
     * @param <S>        the scope type
     * @param stateId    the scope's state row
     * @param scopeKey   the encoded scope key
     * @param scope      the scope, reloaded at the start of the walk
     * @param stamp      the generation to stamp on members this walk sees
     * @param runId      the run
     */
    public record Walk<S>(UUID stateId, String scopeKey, S scope, long stamp, UUID runId) {
    }

    /**
     * Applies a page and checkpoints the cursor that follows it.
     *
     * @param <S>         the scope type
     * @param <SK>        the scope key type
     * @param <M>         the item type
     * @param <MK>        the member key type
     * @param enumeration the enumeration
     * @param walk        the walk this page belongs to
     * @param items       the page
     * @param nextCursor  the token of the page after this one, or null after the last
     * @return what was done with the page's items
     */
    public <S, SK, M, MK> PageOutcome applyPage(RegisteredEnumeration<S, SK, M, MK> enumeration,
                                                Walk<S> walk,
                                                List<M> items,
                                                String nextCursor) {
        Map<String, M> byKey = keyed(enumeration.task(), items);
        Instant highWater = highWater(enumeration.task(), items);
        try {
            PageOutcome outcome = transactions.execute(status -> {
                PageOutcome applied = applyAll(enumeration, walk, byKey);
                states.checkpoint(walk.stateId(), nextCursor, highWater);
                return applied;
            });
            record(enumeration.name(), outcome);
            return outcome;
        } catch (RuntimeException pageFailure) {
            // The integration's code is application code calling into a domain: anything can come out
            // of it. Whatever it was rolled the whole page back, so nothing below double-applies.
            log.debug("Enumeration '{}' could not apply a page of scope {} in one transaction; "
                    + "replaying it item by item", enumeration.name(), walk.scopeKey(), pageFailure);
        }
        PageOutcome outcome = applyOneByOne(enumeration, walk, byKey);
        transactions.executeWithoutResult(status -> states.checkpoint(walk.stateId(), nextCursor, highWater));
        record(enumeration.name(), outcome);
        return outcome;
    }

    private <S, SK, M, MK> PageOutcome applyAll(RegisteredEnumeration<S, SK, M, MK> enumeration,
                                                Walk<S> walk,
                                                Map<String, M> byKey) {
        Map<String, SyncScopeMember> existing = new LinkedHashMap<>();
        members.findMembers(enumeration.name(), walk.scopeKey(), byKey.keySet())
                .forEach(member -> existing.put(member.getMemberKey(), member));
        List<String> seenOnly = new ArrayList<>();
        PageOutcome outcome = PageOutcome.NONE;
        for (Map.Entry<String, M> entry : byKey.entrySet()) {
            outcome = outcome.plus(applyOne(enumeration, walk, entry.getKey(), entry.getValue(),
                    existing.get(entry.getKey()), seenOnly));
        }
        // One statement for every member the page merely confirmed, rather than one write each.
        members.markSeen(enumeration.name(), walk.scopeKey(), seenOnly, walk.stamp(), Instant.now());
        return outcome;
    }

    private <S, SK, M, MK> PageOutcome applyOneByOne(RegisteredEnumeration<S, SK, M, MK> enumeration,
                                                     Walk<S> walk,
                                                     Map<String, M> byKey) {
        PageOutcome outcome = PageOutcome.NONE;
        for (Map.Entry<String, M> entry : byKey.entrySet()) {
            String memberKey = entry.getKey();
            try {
                outcome = outcome.plus(transactions.execute(status -> {
                    List<String> seenOnly = new ArrayList<>();
                    PageOutcome one = applyOne(enumeration, walk, memberKey, entry.getValue(),
                            members.findMember(enumeration.name(), walk.scopeKey(), memberKey).orElse(null),
                            seenOnly);
                    members.markSeen(enumeration.name(), walk.scopeKey(), seenOnly, walk.stamp(), Instant.now());
                    return one;
                }));
            } catch (RuntimeException memberFailure) {
                recordFailure(enumeration.settings(), walk, memberKey, memberFailure);
                outcome = outcome.plus(new PageOutcome(0, 0, 0, 1, 0));
            }
        }
        return outcome;
    }

    /**
     * Decides what one item needs and does it. Runs inside a transaction opened by the caller.
     */
    private <S, SK, M, MK> PageOutcome applyOne(RegisteredEnumeration<S, SK, M, MK> enumeration,
                                                Walk<S> walk,
                                                String memberKey,
                                                M item,
                                                SyncScopeMember existing,
                                                List<String> seenOnly) {
        EnumerationTask<S, SK, M, MK> task = enumeration.task();
        String hash = payloadCodec.hash(item);
        if (existing != null) {
            if (existing.getStatus() == ScopeMemberStatus.QUARANTINED || isBackingOff(existing)) {
                // Seen, so the walk knows the partner still lists it; not handed over, so the walk
                // is not clean and its scope will not sweep on the strength of it.
                seenOnly.add(memberKey);
                return new PageOutcome(0, 0, 0, 0, 1);
            }
            if (existing.getStatus() == ScopeMemberStatus.ACTIVE && hash.equals(existing.getPayloadHash())) {
                seenOnly.add(memberKey);
                return new PageOutcome(0, 1, 0, 0, 0);
            }
        }

        MemberContext context = new MemberContext(enumeration.name(), walk.scopeKey(), memberKey,
                existing == null, walk.runId());
        MemberResult result = task.reconciler().apply(walk.scope(), item, context);
        if (result instanceof MemberResult.Skipped) {
            if (existing != null) {
                // The item is still listed but no longer wanted, which for this scope is leaving it.
                reportLeft(enumeration, walk.scope(), existing, context);
            }
            return new PageOutcome(0, 0, 1, 0, 0);
        }

        Instant now = Instant.now();
        SyncScopeMember member = existing == null ? newMember(enumeration.name(), walk.scopeKey(), memberKey, now)
                : existing;
        member.setPayloadHash(hash);
        member.setStatus(ScopeMemberStatus.ACTIVE);
        member.setAttempts(0);
        member.setNextAttemptAt(null);
        member.setLastError(null);
        member.setSeenGeneration(Math.max(member.getSeenGeneration(), walk.stamp()));
        member.setUpdatedAt(now);
        members.save(member);
        return result instanceof MemberResult.Applied
                ? new PageOutcome(1, 0, 0, 0, 0) : new PageOutcome(0, 1, 0, 0, 0);
    }

    /**
     * Tells the integration a member left a scope and forgets the membership. Runs inside a
     * transaction opened by the caller, so the integration's write and the deleted row commit
     * together.
     */
    <S, SK, M, MK> void reportLeft(RegisteredEnumeration<S, SK, M, MK> enumeration,
                                   S scope,
                                   SyncScopeMember member,
                                   MemberContext context) {
        EnumerationTask<S, SK, M, MK> task = enumeration.task();
        LeftResult result = task.reconciler().left(scope,
                task.memberKeyCodec().decode(member.getMemberKey()), context);
        members.delete(member);
        metrics.recordMemberOutcome(enumeration.name(), "left");
        if (result != LeftResult.HANDLED) {
            metrics.recordMemberLeftUnhandled(enumeration.name());
        }
        if (enumeration.settings().auditEnabled()) {
            auditSink.record(ReconciliationAuditEvent.builder(enumeration.name(), Category.SCOPE,
                            "scope.member.left")
                    .subject(member.getScopeKey() + "/" + member.getMemberKey())
                    .detail(result == LeftResult.HANDLED ? "handled by the integration"
                            : "kept: the integration defines no removal behaviour")
                    .runId(context.runId())
                    .build());
        }
    }

    private void recordFailure(EnumerationSettings settings, Walk<?> walk, String memberKey, RuntimeException cause) {
        String reason = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        boolean quarantined = Boolean.TRUE.equals(transactions.execute(status -> {
            Instant now = Instant.now();
            SyncScopeMember member = members.findMember(settings.name(), walk.scopeKey(), memberKey)
                    .orElseGet(() -> newMember(settings.name(), walk.scopeKey(), memberKey, now));
            member.setAttempts(member.getAttempts() + 1);
            member.setLastError(reason);
            // Cleared: nothing is known to have been applied for the payload now being listed, so
            // the next attempt must not be short-circuited as unchanged.
            member.setPayloadHash(null);
            member.setSeenGeneration(Math.max(member.getSeenGeneration(), walk.stamp()));
            boolean exhausted = member.getAttempts() >= settings.retry().maxAttempts();
            if (exhausted) {
                member.setStatus(ScopeMemberStatus.QUARANTINED);
                member.setNextAttemptAt(null);
            } else {
                member.setStatus(ScopeMemberStatus.FAILED);
                member.setNextAttemptAt(now.plus(
                        new BackoffCalculator(settings.retry().backoff()).nextDelay(member.getAttempts())));
            }
            member.setUpdatedAt(now);
            members.save(member);
            return exhausted;
        }));
        if (quarantined) {
            log.warn("Enumeration '{}' quarantined member {} of scope {} after {} attempt(s)",
                    settings.name(), memberKey, walk.scopeKey(), settings.retry().maxAttempts(), cause);
            if (settings.auditEnabled()) {
                auditSink.record(ReconciliationAuditEvent.builder(settings.name(), Category.SCOPE,
                                "scope.member.quarantined")
                        .subject(walk.scopeKey() + "/" + memberKey).detail(reason).runId(walk.runId()).build());
            }
        } else {
            log.debug("Enumeration '{}' will retry member {} of scope {}",
                    settings.name(), memberKey, walk.scopeKey(), cause);
        }
        metrics.recordMemberOutcome(settings.name(), quarantined ? "quarantined" : "failed");
    }

    private void record(String task, PageOutcome outcome) {
        count(task, "applied", outcome.applied());
        count(task, "unchanged", outcome.unchanged());
        count(task, "skipped", outcome.skipped());
    }

    private void count(String task, String outcome, int times) {
        for (int i = 0; i < times; i++) {
            metrics.recordMemberOutcome(task, outcome);
        }
    }

    private static boolean isBackingOff(SyncScopeMember member) {
        return member.getStatus() == ScopeMemberStatus.FAILED
                && member.getNextAttemptAt() != null
                && member.getNextAttemptAt().isAfter(Instant.now());
    }

    private static SyncScopeMember newMember(String taskName, String scopeKey, String memberKey, Instant now) {
        SyncScopeMember member = new SyncScopeMember();
        member.setTaskName(taskName);
        member.setScopeKey(scopeKey);
        member.setMemberKey(memberKey);
        member.setFirstSeenAt(now);
        member.setUpdatedAt(now);
        return member;
    }

    /**
     * Indexes a page by encoded member key. A key listed twice on one page keeps its last item: the
     * partner said two things about one member in one answer, and the later one is the one it left
     * standing.
     */
    private static <S, SK, M, MK> Map<String, M> keyed(EnumerationTask<S, SK, M, MK> task, List<M> items) {
        Map<String, M> byKey = new LinkedHashMap<>();
        for (M item : items) {
            byKey.put(task.memberKeyCodec().encode(task.memberKey().apply(item)), item);
        }
        return byKey;
    }

    private static <S, SK, M, MK> Instant highWater(EnumerationTask<S, SK, M, MK> task, List<M> items) {
        Instant newest = null;
        for (M item : items) {
            Instant timestamp = task.stampOf(item).timestamp();
            if (timestamp != null && (newest == null || timestamp.isAfter(newest))) {
                newest = timestamp;
            }
        }
        return newest;
    }
}
