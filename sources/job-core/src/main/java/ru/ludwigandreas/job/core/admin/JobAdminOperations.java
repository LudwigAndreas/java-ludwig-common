package ru.ludwigandreas.job.core.admin;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.operation.Operation;
import ru.ludwigandreas.security.operation.OperationContributor;
import ru.ludwigandreas.security.operation.Sensitivity;

/**
 * The {@code job} area's administrative operations.
 *
 * <table>
 *   <caption>Operations</caption>
 *   <tr><th>Operation</th><th>Sensitivity</th><th>Endpoints</th></tr>
 *   <tr><td>{@code job.definition.read}</td><td>READ</td><td>list jobs</td></tr>
 *   <tr><td>{@code job.definition.run}</td><td>CHANGE</td><td>run a job now</td></tr>
 *   <tr><td>{@code job.lease.read}</td><td>READ</td><td>list run leases</td></tr>
 *   <tr><td>{@code job.lease.release}</td><td>PRIVILEGED</td><td>force-release a lease</td></tr>
 * </table>
 */
public final class JobAdminOperations implements OperationContributor {

    /** See the scheduled jobs. */
    public static final String DEFINITION_READ = "job.definition.read";

    /** Run a scheduled job now. */
    public static final String DEFINITION_RUN = "job.definition.run";

    /** See every run lease and who holds it. */
    public static final String LEASE_READ = "job.lease.read";

    /** Free a run lease whatever instance holds it. Privileged: work between renewals may run twice. */
    public static final String LEASE_RELEASE = "job.lease.release";

    private static final String KEY = "ludwig.job-core.admin.";

    @Override
    public Collection<Operation> operations() {
        return List.of(Operation.admin(DEFINITION_READ, Sensitivity.READ, KEY + DEFINITION_READ),
                Operation.admin(DEFINITION_RUN, Sensitivity.CHANGE, KEY + DEFINITION_RUN),
                Operation.admin(LEASE_READ, Sensitivity.READ, KEY + LEASE_READ),
                Operation.admin(LEASE_RELEASE, Sensitivity.PRIVILEGED, KEY + LEASE_RELEASE));
    }
}
