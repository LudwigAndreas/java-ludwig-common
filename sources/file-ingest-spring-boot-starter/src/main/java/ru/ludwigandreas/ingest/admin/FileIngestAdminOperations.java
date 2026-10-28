package ru.ludwigandreas.ingest.admin;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.admin.AdminOperation;
import ru.ludwigandreas.security.admin.AdminOperationContributor;

/**
 * The {@code fileingest} area's administrative operations.
 */
public final class FileIngestAdminOperations implements AdminOperationContributor {

    /** See ingest tasks and their runs. */
    public static final String READ = "fileingest.read";

    /** Trigger a pass: not destructive, since identity and checkpoint still hold, but unplanned load. */
    public static final String OPERATE = "fileingest.operate";

    /** Reserved; guards no endpoint yet. */
    public static final String PRIVILEGED = "fileingest.privileged";

    private static final String KEY = "ludwig.ingest.admin.";

    @Override
    public Collection<AdminOperation> adminOperations() {
        return List.of(AdminOperation.of(READ, KEY + READ), AdminOperation.of(OPERATE, KEY + OPERATE),
                AdminOperation.of(PRIVILEGED, KEY + PRIVILEGED));
    }
}
