package ru.ludwigandreas.usersettings.admin;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.admin.AdminOperation;
import ru.ludwigandreas.security.admin.AdminOperationContributor;

/**
 * The {@code settings} area's administrative operations.
 */
public final class SettingsAdminOperations implements AdminOperationContributor {

    /** The area every operation below belongs to. */
    public static final String AREA = "settings";

    /** Read another subject's resolved settings and consent history. */
    public static final String READ = "settings.read";

    /** Write settings at a user, role or tenant scope, and run a backfill. */
    public static final String OPERATE = "settings.operate";

    /** Reserved; guards no endpoint yet. */
    public static final String PRIVILEGED = "settings.privileged";

    private static final String KEY = "ludwig.user-settings.admin.";

    @Override
    public Collection<AdminOperation> adminOperations() {
        return List.of(AdminOperation.of(READ, KEY + READ), AdminOperation.of(OPERATE, KEY + OPERATE),
                AdminOperation.of(PRIVILEGED, KEY + PRIVILEGED));
    }
}
