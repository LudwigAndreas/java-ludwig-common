package ru.ludwigandreas.export.admin;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.admin.AdminOperation;
import ru.ludwigandreas.security.admin.AdminOperationContributor;

/**
 * The {@code exports} area's administrative operations.
 */
public final class ExportAdminOperations implements AdminOperationContributor {

    /** See every requester's runs and every subscription, without any output. */
    public static final String READ = "exports.read";

    /** Cancel anybody's run. */
    public static final String OPERATE = "exports.operate";

    /** Reserved; guards no endpoint yet. */
    public static final String PRIVILEGED = "exports.privileged";

    private static final String KEY = "ludwig.export.admin.";

    @Override
    public Collection<AdminOperation> adminOperations() {
        return List.of(AdminOperation.of(READ, KEY + READ), AdminOperation.of(OPERATE, KEY + OPERATE),
                AdminOperation.of(PRIVILEGED, KEY + PRIVILEGED));
    }
}
