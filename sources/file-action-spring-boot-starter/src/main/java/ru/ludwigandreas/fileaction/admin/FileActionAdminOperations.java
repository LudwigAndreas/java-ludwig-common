package ru.ludwigandreas.fileaction.admin;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.admin.AdminOperation;
import ru.ludwigandreas.security.admin.AdminOperationContributor;

/**
 * The {@code fileactions} area's administrative operations.
 */
public final class FileActionAdminOperations implements AdminOperationContributor {

    /** See every submitter's submissions, without the file or any row. */
    public static final String READ = "fileactions.read";

    /** Cancel anybody's submission. */
    public static final String OPERATE = "fileactions.operate";

    /** Reserved; guards no endpoint yet. */
    public static final String PRIVILEGED = "fileactions.privileged";

    private static final String KEY = "ludwig.file-action.admin.";

    @Override
    public Collection<AdminOperation> adminOperations() {
        return List.of(AdminOperation.of(READ, KEY + READ), AdminOperation.of(OPERATE, KEY + OPERATE),
                AdminOperation.of(PRIVILEGED, KEY + PRIVILEGED));
    }
}
