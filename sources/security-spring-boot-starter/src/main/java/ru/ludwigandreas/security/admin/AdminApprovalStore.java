package ru.ludwigandreas.security.admin;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Where four-eyes approvals are kept.
 *
 * <p>Declared here, implemented downstream - {@code job-core} ships the JDBC one - because this module has no
 * database. Every state change is conditional on the state it moves from, so two approvers racing produce one
 * execution: the second transition finds the row already moved and reports false.
 */
public interface AdminApprovalStore {

    /**
     * Records a new approval as given.
     *
     * @param approval the approval, {@code PENDING}
     */
    void create(AdminApproval approval);

    /**
     * One approval.
     *
     * @param id the approval id
     * @return it, or empty
     */
    Optional<AdminApproval> find(UUID id);

    /**
     * Approvals of some operations, newest first, after a keyset position.
     *
     * @param operations       the operations to include; empty yields nothing
     * @param state            only this state, or null for every state
     * @param afterRequestedAt the previous page's last request time, or null for the first page
     * @param afterId          the previous page's last id, or null for the first page
     * @param limit            the most to return
     * @return the approvals
     */
    List<AdminApproval> list(Collection<String> operations, AdminApprovalState state, Instant afterRequestedAt,
                             UUID afterId, int limit);

    /**
     * Moves an approval from one state to another, if it is still in the first. Moving to a terminal state drops
     * the stored request body.
     *
     * @param id        the approval id
     * @param from      the state it must be in
     * @param to        the state it moves to
     * @param decidedBy who decided, or null to keep the recorded one
     * @param at        when
     * @param outcome   the executed request's outcome, or null
     * @return whether it moved
     */
    boolean transition(UUID id, AdminApprovalState from, AdminApprovalState to, String decidedBy, Instant at,
                       AdminApproval.Outcome outcome);

    /**
     * Expires every pending approval whose time has run out.
     *
     * @param now the current time
     * @return the approvals expired
     */
    List<AdminApproval> expire(Instant now);

    /**
     * Marks as interrupted every approval left executing since before the cutoff - its replica stopped mid-way.
     *
     * @param approvedBefore the cutoff
     * @return the approvals interrupted
     */
    List<AdminApproval> interrupt(Instant approvedBefore);
}
