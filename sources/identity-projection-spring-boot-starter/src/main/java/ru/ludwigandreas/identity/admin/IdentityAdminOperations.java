package ru.ludwigandreas.identity.admin;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.admin.AdminOperation;
import ru.ludwigandreas.security.admin.AdminOperationContributor;

/**
 * The {@code identity} area's administrative operations. Every change here changes who may access what, so every
 * change is privileged.
 */
public final class IdentityAdminOperations implements AdminOperationContributor {

    /** See partners, grants and a subject's effective authorities. */
    public static final String READ = "identity.read";

    /** Reserved; guards no endpoint yet. */
    public static final String OPERATE = "identity.operate";

    /** Onboard, change, suspend and reactivate partners; grant and revoke data scopes. */
    public static final String PRIVILEGED = "identity.privileged";

    private static final String KEY = "ludwig.identity.admin.";

    @Override
    public Collection<AdminOperation> adminOperations() {
        return List.of(AdminOperation.of(READ, KEY + READ), AdminOperation.of(OPERATE, KEY + OPERATE),
                AdminOperation.of(PRIVILEGED, KEY + PRIVILEGED));
    }
}
