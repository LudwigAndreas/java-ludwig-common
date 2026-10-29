package ru.ludwigandreas.reconciliation.admin;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.admin.AdminOperation;
import ru.ludwigandreas.security.admin.AdminOperationContributor;

/**
 * The {@code reconciliation} area's administrative operations.
 */
public final class ReconciliationAdminOperations implements AdminOperationContributor {

    /** See tasks, enumerations, scopes and quotas. */
    public static final String READ = "reconciliation.read";

    /** Run, backfill, requeue and reset: safe to repeat, and none of it puts this system and the partner apart. */
    public static final String OPERATE = "reconciliation.operate";

    /** Cancel a remote job, release a quota lease, confirm a withheld sweep. */
    public static final String PRIVILEGED = "reconciliation.privileged";

    /** The answer for a task that does not exist - and for one outside a read caller's scope. */
    public static final String TASK_NOT_FOUND = "ludwig.reconciliation.admin.task-not-found";

    /** The answer for an enumeration that does not exist - and for one outside a read caller's scope. */
    public static final String ENUMERATION_NOT_FOUND = "ludwig.reconciliation.admin.enumeration-not-found";

    private static final String KEY = "ludwig.reconciliation.admin.";

    @Override
    public Collection<AdminOperation> adminOperations() {
        return List.of(AdminOperation.of(READ, KEY + READ), AdminOperation.of(OPERATE, KEY + OPERATE),
                AdminOperation.of(PRIVILEGED, KEY + PRIVILEGED));
    }
}
