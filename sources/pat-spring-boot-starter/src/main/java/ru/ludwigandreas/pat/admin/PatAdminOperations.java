package ru.ludwigandreas.pat.admin;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.admin.AdminOperation;
import ru.ludwigandreas.security.admin.AdminOperationContributor;

/**
 * The {@code pat} area's administrative operations.
 */
public final class PatAdminOperations implements AdminOperationContributor {

    /** See any owner's tokens' metadata, never a secret or its hash. */
    public static final String READ = "pat.read";

    /** Reserved; guards no endpoint yet. */
    public static final String OPERATE = "pat.operate";

    /** Issue a token for another owner, and revoke anybody's. */
    public static final String PRIVILEGED = "pat.privileged";

    private static final String KEY = "ludwig.pat.admin.";

    @Override
    public Collection<AdminOperation> adminOperations() {
        return List.of(AdminOperation.of(READ, KEY + READ), AdminOperation.of(OPERATE, KEY + OPERATE),
                AdminOperation.of(PRIVILEGED, KEY + PRIVILEGED));
    }
}
