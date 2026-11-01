package ru.ludwigandreas.usersettings.admin;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.operation.Operation;
import ru.ludwigandreas.security.operation.OperationContributor;
import ru.ludwigandreas.security.operation.Sensitivity;

/**
 * The {@code settings} administrative area: another subject's settings and consents, writes at a user, role or
 * tenant scope, and the backfill.
 */
public final class SettingsAdminOperations implements OperationContributor {

    /** Read another subject's resolved settings. */
    public static final String SUBJECT_READ = "settings.subject.read";

    /** Read another subject's consent history: personal data, so a read of its own. */
    public static final String CONSENT_READ = "settings.consent.read";

    /** Write settings at a user, role or tenant scope. */
    public static final String SCOPE_SET = "settings.scope.set";

    /** Run a backfill. */
    public static final String BACKFILL_START = "settings.backfill.start";

    private static final String KEY = "ludwig.user-settings.admin.";

    /** The area every operation above belongs to. */
    public static final String AREA = "settings";

    @Override
    public Collection<Operation> operations() {
        return List.of(Operation.admin(SUBJECT_READ, Sensitivity.READ, KEY + SUBJECT_READ),
                Operation.admin(CONSENT_READ, Sensitivity.READ, KEY + CONSENT_READ),
                Operation.admin(SCOPE_SET, Sensitivity.CHANGE, KEY + SCOPE_SET),
                Operation.admin(BACKFILL_START, Sensitivity.CHANGE, KEY + BACKFILL_START));
    }
}
