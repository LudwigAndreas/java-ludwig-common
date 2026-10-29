package ru.ludwigandreas.job.core.admin;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.admin.AdminOperation;
import ru.ludwigandreas.security.admin.AdminOperationContributor;

/**
 * The {@code replicas} administrative area. Reading the live replicas and a command's acknowledgements is
 * {@code replicas.read}; submitting a control is authorized by the operation the control itself declares, so the
 * operate and privileged tiers are reserved.
 */
public final class ReplicaAdminOperations implements AdminOperationContributor {

    /** See the live replicas and every command's acknowledgements. */
    public static final String READ = "replicas.read";

    /** Reserved: controls declare their own module's operations. */
    public static final String OPERATE = "replicas.operate";

    /** Reserved: controls declare their own module's operations. */
    public static final String PRIVILEGED = "replicas.privileged";

    private static final String KEY = "ludwig.job-core.admin.";

    @Override
    public Collection<AdminOperation> adminOperations() {
        return List.of(AdminOperation.of(READ, KEY + READ), AdminOperation.of(OPERATE, KEY + OPERATE),
                AdminOperation.of(PRIVILEGED, KEY + PRIVILEGED));
    }
}
