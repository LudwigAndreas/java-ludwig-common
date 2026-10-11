package ru.ludwigandreas.reconciliation.repository;

import com.querydsl.jpa.impl.JPADeleteClause;
import com.querydsl.jpa.impl.JPAUpdateClause;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.reconciliation.entity.QSyncScopeMember;
import ru.ludwigandreas.reconciliation.entity.ScopeMemberStatus;

import java.time.Instant;
import java.util.Collection;

/**
 * Found by Spring Data through the {@code ...Impl} naming convention; needs no annotation and no
 * registration.
 *
 * <p>QueryDSL update and delete clauses against the generated Q-type. The single statement that would
 * replace "mark the seen rows, insert the new ones" is {@code INSERT ... ON CONFLICT}, which
 * QueryDSL-JPA cannot express, and taking it would have meant a SQL carve-out for this module. Two
 * statements per page were preferred to a third exception to the rule.
 */
public class SyncScopeMemberRepositoryCustomImpl implements SyncScopeMemberRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public long markSeen(String taskName, String scopeKey, Collection<String> memberKeys,
                         long generation, Instant now) {
        if (memberKeys.isEmpty()) {
            return 0;
        }
        QSyncScopeMember member = QSyncScopeMember.syncScopeMember;
        return new JPAUpdateClause(entityManager, member)
                .set(member.seenGeneration, generation)
                .set(member.updatedAt, now)
                .where(member.taskName.eq(taskName)
                        .and(member.scopeKey.eq(scopeKey))
                        .and(member.memberKey.in(memberKeys))
                        .and(member.seenGeneration.lt(generation)))
                .execute();
    }

    @Override
    @Transactional
    public long deleteScope(String taskName, String scopeKey) {
        QSyncScopeMember member = QSyncScopeMember.syncScopeMember;
        return new JPADeleteClause(entityManager, member)
                .where(member.taskName.eq(taskName).and(member.scopeKey.eq(scopeKey)))
                .execute();
    }

    @Override
    @Transactional
    public long requeueQuarantined(String taskName, Instant now) {
        QSyncScopeMember member = QSyncScopeMember.syncScopeMember;
        return new JPAUpdateClause(entityManager, member)
                .set(member.status, ScopeMemberStatus.ACTIVE)
                .set(member.attempts, 0)
                .setNull(member.nextAttemptAt)
                // Cleared so that the next walk to list the member applies it whatever its payload:
                // nothing is known to have been applied for it since it failed.
                .setNull(member.payloadHash)
                .set(member.updatedAt, now)
                .where(member.taskName.eq(taskName).and(member.status.eq(ScopeMemberStatus.QUARANTINED)))
                .execute();
    }
}
