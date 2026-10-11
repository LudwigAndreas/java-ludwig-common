package ru.ludwigandreas.reconciliation.engine.enumeration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.job.core.backoff.BackoffCalculator;
import ru.ludwigandreas.reconciliation.api.MemberContext;
import ru.ludwigandreas.reconciliation.audit.ReconciliationAuditEvent;
import ru.ludwigandreas.reconciliation.audit.ReconciliationAuditEvent.Category;
import ru.ludwigandreas.reconciliation.config.EnumerationSettings;
import ru.ludwigandreas.reconciliation.entity.ScopeMemberStatus;
import ru.ludwigandreas.reconciliation.entity.SyncScopeMember;
import ru.ludwigandreas.reconciliation.repository.SyncScopeMemberRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reports the members a completed full walk did not see.
 *
 * <p>This is the only place an enumeration tells an integration that something is gone, so every
 * path into it is guarded, and each guard is against a failure that produces no error anywhere:
 *
 * <ol>
 *   <li><b>Evidence.</b> Only a full walk that reached its last page proves absence - the caller
 *       guarantees that much - and only a clean one is trusted: if the walk listed a member it could
 *       not apply, the scope's local state is already known to be wrong, and removing things on top
 *       of that compounds it.</li>
 *   <li><b>Plausibility.</b> A partner that answers 200 with an empty list during an outage, or after
 *       a permissions change, looks exactly like a partner whose every item was deleted. A sweep
 *       larger than the configured share of the scope is withheld for an operator.</li>
 *   <li><b>Drift.</b> Over offset paging an item can shift between pages mid-walk and be missed once
 *       without being gone, so a task may require several consecutive misses.</li>
 * </ol>
 *
 * <p>The engine removes nothing of the integration's. It calls {@code left}, and deletes its own
 * membership row - which is what makes a member that comes back arrive flagged as new.
 */
public class SweepService {

    /** Members reported per batch. Each is its own transaction; the batch only bounds one read. */
    private static final int BATCH_SIZE = 200;

    private static final Logger log = LoggerFactory.getLogger(SweepService.class);

    private final SyncScopeMemberRepository members;
    private final ScopeStateService states;
    private final MemberPageApplier applier;
    private final AuditSink auditSink;
    private final TransactionTemplate transactions;

    /**
     * Creates the service.
     *
     * @param members            the membership table
     * @param states             scope state, for a withheld sweep
     * @param applier            reports one member as having left
     * @param auditSink          the audit trail
     * @param transactionManager the transaction manager
     */
    public SweepService(SyncScopeMemberRepository members,
                        ScopeStateService states,
                        MemberPageApplier applier,
                        AuditSink auditSink,
                        PlatformTransactionManager transactionManager) {
        this.members = members;
        this.states = states;
        this.applier = applier;
        this.auditSink = auditSink;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** What a sweep did. */
    public enum Verdict {

        /** No member was unseen. */
        NOTHING_TO_SWEEP,

        /** The walk listed a member it could not apply, so its evidence was not trusted. */
        NOT_CLEAN,

        /** The sweep would have reported more than the configured share of the scope. */
        HELD,

        /** The unseen members were reported. */
        SWEPT
    }

    /**
     * The outcome of a sweep.
     *
     * @param verdict    what was decided
     * @param candidates how many members were unseen
     * @param reported   how many were reported as having left
     */
    public record Result(Verdict verdict, long candidates, int reported) {
    }

    /**
     * Sweeps a scope after a full walk completed.
     *
     * @param <S>         the scope type
     * @param <SK>        the scope key type
     * @param <M>         the item type
     * @param <MK>        the member key type
     * @param enumeration the enumeration
     * @param stateId     the scope's state row
     * @param scopeKey    the encoded scope key
     * @param scope       the scope
     * @param generation  the scope's generation, already advanced by the completed walk
     * @param runId       the run
     * @param confirmed   true when an operator has confirmed a withheld sweep, which skips the
     *                    evidence and plausibility guards - the operator is the evidence
     * @return what was done
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public <S, SK, M, MK> Result sweep(RegisteredEnumeration<S, SK, M, MK> enumeration,
                                       UUID stateId,
                                       String scopeKey,
                                       S scope,
                                       long generation,
                                       UUID runId,
                                       boolean confirmed) {
        EnumerationSettings settings = enumeration.settings();
        String name = settings.name();
        long lastUnseen = generation - settings.sweepConfirmAfterMisses();

        if (!confirmed && members.countNotActiveSeen(name, scopeKey, generation) > 0) {
            log.debug("Enumeration '{}' did not sweep scope {}: the walk listed members it could not "
                    + "apply", name, scopeKey);
            return new Result(Verdict.NOT_CLEAN, 0, 0);
        }

        long candidates = members.countUnseen(name, scopeKey, lastUnseen);
        if (candidates == 0) {
            states.releaseSweep(stateId);
            return new Result(Verdict.NOTHING_TO_SWEEP, 0, 0);
        }

        long total = members.countMembers(name, scopeKey);
        if (!confirmed && candidates > settings.sweepMaxFraction() * total) {
            states.holdSweep(stateId, (int) Math.min(candidates, Integer.MAX_VALUE));
            log.warn("Enumeration '{}' withheld a sweep of scope {}: {} of {} member(s) were not "
                            + "listed, more than sweep.max-fraction ({}) allows without an operator. "
                            + "Confirm or discard it through the reconciliation endpoint.",
                    name, scopeKey, candidates, total, settings.sweepMaxFraction());
            if (settings.auditEnabled()) {
                auditSink.record(ReconciliationAuditEvent.builder(name, Category.SCOPE, "scope.sweep.held")
                        .subject(scopeKey)
                        .detail(candidates + " of " + total + " member(s) not listed")
                        .runId(runId)
                        .build());
            }
            return new Result(Verdict.HELD, candidates, 0);
        }

        int reported = 0;
        while (true) {
            List<SyncScopeMember> batch = members.findUnseen(name, scopeKey, lastUnseen, Instant.now(), BATCH_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            for (SyncScopeMember member : batch) {
                if (reportLeft(enumeration, scope, member.getId(), runId)) {
                    reported++;
                }
            }
        }
        states.releaseSweep(stateId);
        return new Result(Verdict.SWEPT, candidates, reported);
    }

    /**
     * Reports one member, in its own transaction. A failure is recorded on the member and backs it
     * off, which is also what takes it out of the batch query so the loop above terminates.
     */
    private <S, SK, M, MK> boolean reportLeft(RegisteredEnumeration<S, SK, M, MK> enumeration,
                                              S scope,
                                              UUID memberId,
                                              UUID runId) {
        try {
            transactions.executeWithoutResult(status -> members.findById(memberId).ifPresent(member ->
                    applier.reportLeft(enumeration, scope, member, new MemberContext(enumeration.name(),
                            member.getScopeKey(), member.getMemberKey(), false, runId))));
            return true;
        } catch (RuntimeException failure) {
            recordFailure(enumeration.settings(), memberId, failure);
            return false;
        }
    }

    private void recordFailure(EnumerationSettings settings, UUID memberId, RuntimeException cause) {
        transactions.executeWithoutResult(status -> members.findById(memberId).ifPresent(member -> {
            Instant now = Instant.now();
            member.setAttempts(member.getAttempts() + 1);
            member.setLastError("left: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            if (member.getAttempts() >= settings.retry().maxAttempts()) {
                member.setStatus(ScopeMemberStatus.QUARANTINED);
                member.setNextAttemptAt(null);
                log.warn("Enumeration '{}' quarantined member {} of scope {}: reporting that it left "
                                + "kept failing", settings.name(), member.getMemberKey(), member.getScopeKey(),
                        cause);
            } else {
                member.setNextAttemptAt(now.plus(
                        new BackoffCalculator(settings.retry().backoff()).nextDelay(member.getAttempts())));
            }
            member.setUpdatedAt(now);
            members.save(member);
        }));
    }
}
