package ru.ludwigandreas.reconciliation.api;

import java.util.UUID;

/**
 * What the engine knows about a member at the moment it hands it to a {@link MemberReconciler}.
 *
 * @param taskName the task
 * @param scopeKey the scope, in the form the task's scope codec writes
 * @param memberKey the member, in the form the task's member codec writes
 * @param isNew    true when the engine holds no membership row for this member in this scope: a first
 *                 sighting, or a member that left and came back. It says nothing about whether the
 *                 integration already has a row for the item - the same item may be a member of
 *                 another scope, or may have been soft-deleted when it left - so an implementation
 *                 looks its own row up, including removed ones, rather than inserting on this flag
 * @param runId    the run, for correlating what was written with the call that fetched it
 */
public record MemberContext(String taskName, String scopeKey, String memberKey, boolean isNew, UUID runId) {
}
