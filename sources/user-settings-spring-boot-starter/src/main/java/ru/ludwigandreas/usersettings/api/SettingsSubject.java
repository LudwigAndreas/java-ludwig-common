package ru.ludwigandreas.usersettings.api;

import ru.ludwigandreas.security.authz.PrincipalRef;

/**
 * Who a lookup is about, with the tenant that lookup is confined to.
 *
 * <p>{@link PrincipalRef} deliberately carries no tenant - it is the security module's key for "which
 * caller", and a caller's entitlements are the same question in every tenant. Settings are not: the
 * whole point of {@link #tenantId()} being on this type, and being required, is that there is no
 * overload of any query in this module that can run without one. A missing tenant is a resolution
 * failure, never an unscoped read, because an unscoped read is exactly the bug that would hand one
 * organization's users another organization's configuration.
 *
 * <p>Note what this type does <em>not</em> mean: it is not a claim that the subject belongs to the
 * tenant. It is the tenant the caller is permitted to read within, which for an administrator
 * reading someone else's settings is the administrator's own. Scoping the query to that, rather than
 * to the target's tenant, is what makes a cross-tenant read return nothing instead of returning data.
 *
 * @param ref      the subject whose settings are being resolved
 * @param tenantId the tenant the resolution is confined to; never blank
 */
public record SettingsSubject(PrincipalRef ref, String tenantId) {

    /** Rejects a subject with no tenant, which is what makes an unscoped lookup unrepresentable. */
    public SettingsSubject {
        if (ref == null) {
            throw new IllegalArgumentException("A settings subject needs a principal reference");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException(
                    "A settings subject needs a tenant; resolve one or reject the lookup (subject=" + ref + ")");
        }
    }

    public String subject() {
        return ref.subject();
    }
}
