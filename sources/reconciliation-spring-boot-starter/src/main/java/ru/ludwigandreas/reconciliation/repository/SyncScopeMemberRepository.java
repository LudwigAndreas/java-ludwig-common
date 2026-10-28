package ru.ludwigandreas.reconciliation.repository;

import com.querydsl.core.types.dsl.BooleanExpression;
import org.springframework.data.querydsl.QPageRequest;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.reconciliation.entity.QSyncScopeMember;
import ru.ludwigandreas.reconciliation.entity.ScopeMemberStatus;
import ru.ludwigandreas.reconciliation.entity.SyncScopeMember;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Scope membership.
 *
 * <p>Every read is a QueryDSL predicate against the generated Q-type; the set-based writes are in
 * {@link SyncScopeMemberRepositoryCustom}.
 */
public interface SyncScopeMemberRepository
        extends BaseRepository<SyncScopeMember, UUID>, SyncScopeMemberRepositoryCustom {

    /**
     * The membership rows for a page of member keys, read once per page rather than once per item.
     *
     * @param taskName   the task
     * @param scopeKey   the encoded scope key
     * @param memberKeys the encoded member keys on the page
     * @return the rows that exist
     */
    default List<SyncScopeMember> findMembers(String taskName, String scopeKey, Collection<String> memberKeys) {
        if (memberKeys.isEmpty()) {
            return List.of();
        }
        QSyncScopeMember member = QSyncScopeMember.syncScopeMember;
        List<SyncScopeMember> found = new ArrayList<>();
        findAll(ofScope(taskName, scopeKey).and(member.memberKey.in(memberKeys))).forEach(found::add);
        return found;
    }

    /**
     * One membership row.
     *
     * @param taskName  the task
     * @param scopeKey  the encoded scope key
     * @param memberKey the encoded member key
     * @return the row, or empty
     */
    default Optional<SyncScopeMember> findMember(String taskName, String scopeKey, String memberKey) {
        return findOne(ofScope(taskName, scopeKey)
                .and(QSyncScopeMember.syncScopeMember.memberKey.eq(memberKey)));
    }

    /**
     * A batch of the members a completed full walk did not see and that may be reported now.
     *
     * <p>Always the first page: the caller deletes each row it reports, and a row whose report failed
     * is pushed out of this predicate by its backoff, so the next call returns the next batch without
     * an offset that the deletions would have invalidated. A quarantined member is left out - it is
     * not handed to the integration again until an operator requeues it.
     *
     * @param taskName       the task
     * @param scopeKey       the encoded scope key
     * @param lastGeneration the newest generation that still counts as unseen
     * @param now            the current instant
     * @param limit          the batch size
     * @return the candidates, in key order
     */
    default List<SyncScopeMember> findUnseen(String taskName, String scopeKey, long lastGeneration,
                                             Instant now, int limit) {
        QSyncScopeMember member = QSyncScopeMember.syncScopeMember;
        return findAll(unseen(taskName, scopeKey, lastGeneration)
                        .and(member.status.ne(ScopeMemberStatus.QUARANTINED))
                        .and(member.nextAttemptAt.isNull().or(member.nextAttemptAt.loe(now))),
                QPageRequest.of(0, limit, member.memberKey.asc())).getContent();
    }

    /**
     * How many members a completed full walk did not see.
     *
     * @param taskName       the task
     * @param scopeKey       the encoded scope key
     * @param lastGeneration the newest generation that still counts as unseen
     * @return the count
     */
    default long countUnseen(String taskName, String scopeKey, long lastGeneration) {
        return count(unseen(taskName, scopeKey, lastGeneration));
    }

    /**
     * How many members a scope has.
     *
     * @param taskName the task
     * @param scopeKey the encoded scope key
     * @return the count
     */
    default long countMembers(String taskName, String scopeKey) {
        return count(ofScope(taskName, scopeKey));
    }

    /**
     * How many members a walk listed and could not apply - which is what makes that walk not clean,
     * and keeps its scope from sweeping.
     *
     * <p>Restricted to members the walk actually saw. A failed member the partner no longer lists
     * would otherwise never be retried, never recover, and block every later sweep of its scope; it is
     * simply a sweep candidate like any other unseen member.
     *
     * @param taskName   the task
     * @param scopeKey   the encoded scope key
     * @param generation the generation the walk stamped
     * @return the count
     */
    default long countNotActiveSeen(String taskName, String scopeKey, long generation) {
        QSyncScopeMember member = QSyncScopeMember.syncScopeMember;
        return count(ofScope(taskName, scopeKey)
                .and(member.status.ne(ScopeMemberStatus.ACTIVE))
                .and(member.seenGeneration.goe(generation)));
    }

    /**
     * How many of a task's members are in a status, across every scope.
     *
     * @param taskName the task
     * @param status   the status
     * @return the count
     */
    default long countByStatus(String taskName, ScopeMemberStatus status) {
        QSyncScopeMember member = QSyncScopeMember.syncScopeMember;
        return count(member.taskName.eq(taskName).and(member.status.eq(status)));
    }

    /**
     * A sample of a task's members in a status, newest failure first, for the administrative endpoints.
     *
     * @param taskName the task
     * @param status   the status
     * @param limit    how many to return
     * @return the sample
     */
    default List<SyncScopeMember> sample(String taskName, ScopeMemberStatus status, int limit) {
        QSyncScopeMember member = QSyncScopeMember.syncScopeMember;
        return findAll(member.taskName.eq(taskName).and(member.status.eq(status)),
                QPageRequest.of(0, limit, member.updatedAt.desc())).getContent();
    }

    /**
     * Every membership row of a scope, for a preview that has to say what a walk would change and
     * what it would miss.
     *
     * @param taskName the task
     * @param scopeKey the encoded scope key
     * @return the rows
     */
    default List<SyncScopeMember> findAllOfScope(String taskName, String scopeKey) {
        List<SyncScopeMember> rows = new ArrayList<>();
        findAll(ofScope(taskName, scopeKey)).forEach(rows::add);
        return rows;
    }

    /**
     * The scopes that hold a member in a status - which is what has to be walked in full again after
     * those members are requeued, since only a full walk is certain to list them.
     *
     * @param taskName the task
     * @param status   the status
     * @return the encoded scope keys, each once
     */
    default java.util.Set<String> findScopeKeysByStatus(String taskName, ScopeMemberStatus status) {
        QSyncScopeMember member = QSyncScopeMember.syncScopeMember;
        java.util.Set<String> scopeKeys = new java.util.LinkedHashSet<>();
        findAll(member.taskName.eq(taskName).and(member.status.eq(status)))
                .forEach(row -> scopeKeys.add(row.getScopeKey()));
        return scopeKeys;
    }

    private static BooleanExpression ofScope(String taskName, String scopeKey) {
        QSyncScopeMember member = QSyncScopeMember.syncScopeMember;
        return member.taskName.eq(taskName).and(member.scopeKey.eq(scopeKey));
    }

    private static BooleanExpression unseen(String taskName, String scopeKey, long lastGeneration) {
        return ofScope(taskName, scopeKey)
                .and(QSyncScopeMember.syncScopeMember.seenGeneration.loe(lastGeneration));
    }
}
