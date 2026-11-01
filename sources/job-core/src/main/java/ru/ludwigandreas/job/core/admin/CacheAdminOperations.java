package ru.ludwigandreas.job.core.admin;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.operation.Operation;
import ru.ludwigandreas.security.operation.OperationContributor;
import ru.ludwigandreas.security.operation.Sensitivity;

/**
 * The {@code cache} administrative area: the caches this service runs, and clearing one on every replica.
 */
public final class CacheAdminOperations implements OperationContributor {

    /** See the registered caches. */
    public static final String DEFINITION_READ = "cache.definition.read";

    /** Clear a performance cache on every replica. */
    public static final String PERFORMANCE_EVICT = "cache.performance.evict";

    /** Clear a security cache - authority, revocation - on every replica: it changes who may do what. */
    public static final String SECURITY_EVICT = "cache.security.evict";

    private static final String KEY = "ludwig.job-core.admin.";

    /** {@link #PERFORMANCE_EVICT}, which the eviction control declares. */
    public static final Operation PERFORMANCE_EVICT_OPERATION =
            Operation.admin(PERFORMANCE_EVICT, Sensitivity.CHANGE, KEY + PERFORMANCE_EVICT);

    /** {@link #SECURITY_EVICT}, which the eviction control declares. */
    public static final Operation SECURITY_EVICT_OPERATION =
            Operation.admin(SECURITY_EVICT, Sensitivity.PRIVILEGED, KEY + SECURITY_EVICT);

    @Override
    public Collection<Operation> operations() {
        return List.of(Operation.admin(DEFINITION_READ, Sensitivity.READ, KEY + DEFINITION_READ),
                PERFORMANCE_EVICT_OPERATION, SECURITY_EVICT_OPERATION);
    }
}
