package ru.ludwigandreas.audit.store.admin;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.admin.AdminOperation;
import ru.ludwigandreas.security.admin.AdminOperationContributor;

/**
 * The {@code audit} area's administrative operations. Only {@code audit.read} guards an endpoint: the trail is
 * append-only, and nothing here changes it.
 */
public final class AuditAdminOperations implements AdminOperationContributor {

    /** Search the stored trail. */
    public static final String READ = "audit.read";

    /** Reserved; guards no endpoint. */
    public static final String OPERATE = "audit.operate";

    /** Reserved; guards no endpoint. */
    public static final String PRIVILEGED = "audit.privileged";

    private static final String KEY = "ludwig.audit.admin.";

    @Override
    public Collection<AdminOperation> adminOperations() {
        return List.of(AdminOperation.of(READ, KEY + READ), AdminOperation.of(OPERATE, KEY + OPERATE),
                AdminOperation.of(PRIVILEGED, KEY + PRIVILEGED));
    }
}
