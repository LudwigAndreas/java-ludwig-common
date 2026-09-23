package ru.ludwigandreas.usersettings.resolve;

import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.usersettings.api.SettingsSubject;
import ru.ludwigandreas.usersettings.exception.TenantUnresolvableException;

/**
 * Turns a {@link PrincipalRef} into a tenant-scoped {@link SettingsSubject}, or refuses.
 *
 * <p>One place rather than one per caller. The lookup, the writer, the consent service and the
 * controllers all need the same three lines, and three of them getting it right while the fourth
 * quietly substitutes a default tenant is precisely the failure mode
 * {@link TenantUnresolvableException} exists to prevent.
 */
public final class SettingsSubjects {

    private SettingsSubjects() {
    }

    /**
     * The subject, with the tenant the caller is permitted to read within.
     *
     * @throws TenantUnresolvableException when no tenant could be determined - deliberately, rather
     *                                     than running the lookup unscoped
     */
    public static SettingsSubject require(SettingsTenantResolver resolver, PrincipalRef ref) {
        return resolver.resolve(ref)
                .map(tenant -> new SettingsSubject(ref, tenant))
                .orElseThrow(() -> new TenantUnresolvableException(ref.subject()));
    }
}
