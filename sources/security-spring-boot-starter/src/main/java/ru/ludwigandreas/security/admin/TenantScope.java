package ru.ludwigandreas.security.admin;

import java.util.Objects;

/**
 * Which tenant's resources an administrative caller may reach.
 *
 * <p>A person is confined to exactly their own tenant - including a person with no tenant, who then reaches only
 * resources that carry none, which is what lets a single-tenant service use this without configuration. A service
 * principal is not confined: it is deployed once for the whole estate and carries no tenant of its own. The rule is
 * the one {@code user-settings} already applies to its administrative access.
 *
 * <p>A module applies it to its tenant-carrying tables only; an outbox row or a run lock carries no tenant and is
 * not confined.
 *
 * @param confined whether a predicate applies at all
 * @param tenant   the tenant a confined caller may reach, possibly null
 */
public record TenantScope(boolean confined, String tenant) {

    private static final TenantScope UNCONFINED = new TenantScope(false, null);

    /**
     * The scope of a caller who reaches every tenant.
     *
     * @return the unconfined scope
     */
    public static TenantScope unconfined() {
        return UNCONFINED;
    }

    /**
     * The scope of a caller confined to one tenant.
     *
     * @param tenant the caller's tenant, or null for a caller with none
     * @return the confined scope
     */
    public static TenantScope confinedTo(String tenant) {
        return new TenantScope(true, tenant);
    }

    /**
     * Whether a resource of this tenant is within reach.
     *
     * @param resourceTenant the resource's tenant, possibly null
     * @return true when unconfined, or when the tenants are equal
     */
    public boolean permits(String resourceTenant) {
        return !confined || Objects.equals(tenant, resourceTenant);
    }
}
