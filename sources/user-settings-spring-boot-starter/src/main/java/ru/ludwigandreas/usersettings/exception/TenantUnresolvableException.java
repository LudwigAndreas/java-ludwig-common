package ru.ludwigandreas.usersettings.exception;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * No tenant could be determined for a lookup, so the lookup was refused.
 *
 * <p>The alternative is the bug this module most needs not to have. A resolution that proceeds
 * without a tenant reads every tenant's rows, and the first value it finds wins - so one
 * organization's user silently receives another organization's configuration, and nothing in the
 * response says so. Refusing is loud, obviously wrong, and fixable; succeeding quietly is none of
 * those.
 *
 * <p>Code running outside a request - a queue worker fanning a notification out, a scheduled job -
 * has no security context to take a tenant from and must call the {@code SettingsSubject} overloads,
 * naming the tenant it already knows from the row it is processing.
 */
public class TenantUnresolvableException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    public TenantUnresolvableException(String subject) {
        super(ProblemStatus.INTERNAL, SettingProblemCodes.TENANT_UNRESOLVABLE, subject);
    }
}
