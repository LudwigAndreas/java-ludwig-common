package ru.ludwigandreas.security.unit;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import ru.ludwigandreas.security.admin.AdminApproval;
import ru.ludwigandreas.security.admin.AdminApprovalState;
import ru.ludwigandreas.security.admin.AdminApprovalStore;

/** The approval store for this module's tests, which have no database; job-core's JDBC store is the real one. */
public class InMemoryAdminApprovalStore implements AdminApprovalStore {

    private final Map<UUID, AdminApproval> approvals = new ConcurrentHashMap<>();

    /** Forgets every approval, between tests. */
    public void clear() {
        approvals.clear();
    }

    @Override
    public void create(AdminApproval approval) {
        approvals.put(approval.id(), approval);
    }

    @Override
    public Optional<AdminApproval> find(UUID id) {
        return Optional.ofNullable(approvals.get(id));
    }

    @Override
    public List<AdminApproval> list(Collection<String> operations, AdminApprovalState state, Instant afterRequestedAt,
                                    UUID afterId, int limit) {
        Comparator<AdminApproval> newestFirst = Comparator.comparing(AdminApproval::requestedAt).reversed()
                .thenComparing(AdminApproval::id);
        return approvals.values().stream()
                .filter(approval -> operations.contains(approval.operation()))
                .filter(approval -> state == null || approval.state() == state)
                .filter(approval -> afterRequestedAt == null
                        || approval.requestedAt().isBefore(afterRequestedAt)
                        || approval.requestedAt().equals(afterRequestedAt) && approval.id().compareTo(afterId) > 0)
                .sorted(newestFirst).limit(limit).toList();
    }

    @Override
    public synchronized boolean transition(UUID id, AdminApprovalState from, AdminApprovalState to, String decidedBy,
                                           Instant at, AdminApproval.Outcome outcome) {
        AdminApproval current = approvals.get(id);
        if (current == null || current.state() != from) {
            return false;
        }
        approvals.put(id, new AdminApproval(current.id(), current.operation(),
                to.isTerminal() ? current.request().withoutBody() : current.request(), current.requestedBy(),
                current.requesterType(), current.requestedAt(), current.expiresAt(), to,
                decidedBy == null ? current.decidedBy() : decidedBy, at,
                outcome == null ? current.outcome() : outcome));
        return true;
    }

    @Override
    public List<AdminApproval> expire(Instant now) {
        return approvals.values().stream()
                .filter(approval -> approval.state() == AdminApprovalState.PENDING
                        && !approval.expiresAt().isAfter(now))
                .filter(approval -> transition(approval.id(), AdminApprovalState.PENDING, AdminApprovalState.EXPIRED,
                        null, now, null))
                .toList();
    }

    @Override
    public List<AdminApproval> interrupt(Instant approvedBefore) {
        return approvals.values().stream()
                .filter(approval -> approval.state() == AdminApprovalState.APPROVING
                        && approval.decidedAt().isBefore(approvedBefore))
                .filter(approval -> transition(approval.id(), AdminApprovalState.APPROVING,
                        AdminApprovalState.INTERRUPTED, null, Instant.now(), null))
                .toList();
    }
}
