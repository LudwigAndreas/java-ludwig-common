package ru.ludwigandreas.job.core.admin;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.admin.AdminOperation;
import ru.ludwigandreas.security.admin.AdminOperationContributor;

/**
 * The {@code caches} administrative area: the caches this service runs, and clearing one on every replica.
 */
public final class CacheAdminOperations implements AdminOperationContributor {

    /** See the registered caches. */
    public static final String READ = "caches.read";

    /** Clear a performance cache on every replica. */
    public static final String OPERATE = "caches.operate";

    /** Clear a security cache on every replica. */
    public static final String PRIVILEGED = "caches.privileged";

    private static final String KEY = "ludwig.job-core.admin.";

    /** The operations as catalog entries, which the eviction control declares. */
    public static final AdminOperation OPERATE_OPERATION = AdminOperation.of(OPERATE, KEY + OPERATE);

    /** See {@link #OPERATE_OPERATION}. */
    public static final AdminOperation PRIVILEGED_OPERATION = AdminOperation.of(PRIVILEGED, KEY + PRIVILEGED);

    @Override
    public Collection<AdminOperation> adminOperations() {
        return List.of(AdminOperation.of(READ, KEY + READ), OPERATE_OPERATION, PRIVILEGED_OPERATION);
    }
}
