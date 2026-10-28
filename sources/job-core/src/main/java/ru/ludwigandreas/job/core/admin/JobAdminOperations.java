package ru.ludwigandreas.job.core.admin;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.admin.AdminOperation;
import ru.ludwigandreas.security.admin.AdminOperationContributor;

/**
 * The {@code jobs} area's administrative operations.
 *
 * <table>
 *   <caption>Operations</caption>
 *   <tr><th>Operation</th><th>Endpoints</th></tr>
 *   <tr><td>{@code jobs.read}</td><td>list jobs and leases</td></tr>
 *   <tr><td>{@code jobs.operate}</td><td>run a job now</td></tr>
 *   <tr><td>{@code jobs.privileged}</td><td>force-release a lease</td></tr>
 * </table>
 */
public final class JobAdminOperations implements AdminOperationContributor {

    /** See the scheduled jobs and their run leases. */
    public static final String READ = "jobs.read";

    /** Run a scheduled job now. */
    public static final String OPERATE = "jobs.operate";

    /** Free a run lease whatever instance holds it. Privileged: work between renewals may run twice. */
    public static final String PRIVILEGED = "jobs.privileged";

    private static final String KEY = "ludwig.job-core.admin.";

    @Override
    public Collection<AdminOperation> adminOperations() {
        return List.of(AdminOperation.of(READ, KEY + READ), AdminOperation.of(OPERATE, KEY + OPERATE),
                AdminOperation.of(PRIVILEGED, KEY + PRIVILEGED));
    }
}
