package ru.ludwigandreas.reconciliation.admin;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.operation.Operation;
import ru.ludwigandreas.security.operation.OperationContributor;
import ru.ludwigandreas.security.operation.Sensitivity;

/**
 * The {@code reconciliation} administrative area: tasks, enumerations and their scopes, runs, remote jobs, quota
 * leases and withheld sweeps. A team can be confined to named tasks through the {@code OBJECT} dimension.
 */
public final class ReconciliationAdminOperations implements OperationContributor {

    /** See tasks, enumerations, scopes, runs and quotas. */
    public static final String TASK_READ = "reconciliation.task.read";

    /** Run a task now. */
    public static final String RUN_START = "reconciliation.run.start";

    /** Backfill a task over named keys. */
    public static final String BACKFILL_START = "reconciliation.backfill.start";

    /** Reset a task's cursor, so its next run starts over. */
    public static final String CURSOR_RESET = "reconciliation.cursor.reset";

    /** Reset an enumeration scope. */
    public static final String SCOPE_RESET = "reconciliation.scope.reset";

    /** Requeue a task's quarantined records. */
    public static final String QUARANTINE_REQUEUE = "reconciliation.quarantine.requeue";

    /** Requeue an enumeration's failed members. */
    public static final String MEMBER_REQUEUE = "reconciliation.member.requeue";

    /** Requeue a remote job. */
    public static final String REMOTEJOB_REQUEUE = "reconciliation.remotejob.requeue";

    /** Cancel a remote job. Privileged: this system and the partner can disagree after it. */
    public static final String REMOTEJOB_CANCEL = "reconciliation.remotejob.cancel";

    /** Release a quota lease. Privileged: the partner's quota can be exceeded. */
    public static final String LEASE_RELEASE = "reconciliation.lease.release";

    /** Discover an enumeration's members now. */
    public static final String DISCOVERY_START = "reconciliation.discovery.start";

    /** Walk an enumeration scope now. */
    public static final String WALK_START = "reconciliation.walk.start";

    /** Preview what a walk of a scope would do. A change: it calls the partner. */
    public static final String WALK_PREVIEW = "reconciliation.walk.preview";

    /** Discard a withheld sweep. */
    public static final String SWEEP_DISCARD = "reconciliation.sweep.discard";

    /** Confirm a withheld sweep. Privileged: rows are deleted. */
    public static final String SWEEP_CONFIRM = "reconciliation.sweep.confirm";

    /** The answer for a task that does not exist - and for one outside a read caller's scope. */
    public static final String TASK_NOT_FOUND = "ludwig.reconciliation.admin.task-not-found";

    /** The answer for an enumeration that does not exist - and for one outside a read caller's scope. */
    public static final String ENUMERATION_NOT_FOUND = "ludwig.reconciliation.admin.enumeration-not-found";

    private static final String KEY = "ludwig.reconciliation.admin.";

    @Override
    public Collection<Operation> operations() {
        return List.of(Operation.admin(TASK_READ, Sensitivity.READ, KEY + TASK_READ),
                Operation.admin(RUN_START, Sensitivity.CHANGE, KEY + RUN_START),
                Operation.admin(BACKFILL_START, Sensitivity.CHANGE, KEY + BACKFILL_START),
                Operation.admin(CURSOR_RESET, Sensitivity.CHANGE, KEY + CURSOR_RESET),
                Operation.admin(SCOPE_RESET, Sensitivity.CHANGE, KEY + SCOPE_RESET),
                Operation.admin(QUARANTINE_REQUEUE, Sensitivity.CHANGE, KEY + QUARANTINE_REQUEUE),
                Operation.admin(MEMBER_REQUEUE, Sensitivity.CHANGE, KEY + MEMBER_REQUEUE),
                Operation.admin(REMOTEJOB_REQUEUE, Sensitivity.CHANGE, KEY + REMOTEJOB_REQUEUE),
                Operation.admin(REMOTEJOB_CANCEL, Sensitivity.PRIVILEGED, KEY + REMOTEJOB_CANCEL),
                Operation.admin(LEASE_RELEASE, Sensitivity.PRIVILEGED, KEY + LEASE_RELEASE),
                Operation.admin(DISCOVERY_START, Sensitivity.CHANGE, KEY + DISCOVERY_START),
                Operation.admin(WALK_START, Sensitivity.CHANGE, KEY + WALK_START),
                Operation.admin(WALK_PREVIEW, Sensitivity.CHANGE, KEY + WALK_PREVIEW),
                Operation.admin(SWEEP_DISCARD, Sensitivity.CHANGE, KEY + SWEEP_DISCARD),
                Operation.admin(SWEEP_CONFIRM, Sensitivity.PRIVILEGED, KEY + SWEEP_CONFIRM));
    }
}
