package ru.ludwigandreas.usersettings.resolve;

import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.security.authz.Authorities;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;
import ru.ludwigandreas.security.principal.SecurityPrincipals;
import ru.ludwigandreas.usersettings.api.SettingsSubject;
import ru.ludwigandreas.usersettings.exception.SettingsAccessDeniedException;

/**
 * "You may read and write your own settings; somebody else's needs the administrative authority."
 *
 * <p>Enforced here, once, rather than in each caller. The rule is the same in every service that
 * uses this module, which is exactly why leaving it to callers does not work: it gets reimplemented
 * per endpoint, and the endpoint that gets it wrong is the one that takes a subject id from the path
 * and passes it through without looking at it.
 *
 * <p>Two conditions have to hold for an administrative access, not one. Holding the authority is not
 * enough - the administrator's own tenant must also match the tenant the lookup is confined to.
 * Without the second check, an administrator of one organization could read another organization's
 * users simply by knowing a subject id, and the authority check would have passed.
 */
@Slf4j
public class SettingsAccessPolicy {

    private final String adminAuthority;
    private final String adminRole;
    private final boolean allowUnauthenticated;

    /**
     * Whether a peer service may resolve a subject's settings.
     *
     * <p>True by default, and the reason is that the self-or-admin rule is about <em>people</em>. It
     * exists so one user cannot read another's preferences; a service authenticated by its own
     * workload identity is not a user and is never the subject of a setting, so measuring it against
     * "is this your own subject id" asks a question with no meaningful answer - and the answer it
     * gets, no, breaks the case the projection exists for: a notification service resolving a
     * recipient's locale while handling a request from another service.
     *
     * <p>What authorizes such a call is the calling service's own policy for the endpoint it invoked,
     * which the security module has already applied by the time this runs. A deployment that wants
     * peers held to the administrative authority anyway sets this false and grants it to them.
     */
    private final boolean allowServicePrincipals;

    /**
     * Builds the policy.
     *
     * @param adminAuthority accepted with or without the {@code ROLE_} prefix; see the field below
     */
    public SettingsAccessPolicy(String adminAuthority, boolean allowUnauthenticated,
                                boolean allowServicePrincipals) {
        if (adminAuthority == null || adminAuthority.isBlank()) {
            throw new IllegalArgumentException(
                    "An administrative authority must be configured; without one, every access to another"
                            + " subject's settings would be refused and no administrative screen could work");
        }
        this.adminAuthority = adminAuthority;
        // Spring Security's hasRole('X') tests for ROLE_X, and LudwigPrincipal stores the prefixed
        // form - so a property written as SETTINGS_ADMIN would match nothing and every administrative
        // access would be refused, which reads as a permissions bug and gets "fixed" by granting more.
        this.adminRole = Authorities.normalizeRole(adminAuthority);
        this.allowUnauthenticated = allowUnauthenticated;
        this.allowServicePrincipals = allowServicePrincipals;
    }

    /**
     * Decides on whose behalf this operation is running, or refuses.
     *
     * @throws SettingsAccessDeniedException when the caller may not touch this subject's settings
     */
    public SettingsAccess check(SettingsSubject subject) {
        Optional<LudwigPrincipal> caller = SecurityPrincipals.current();
        if (caller.isEmpty()) {
            if (allowUnauthenticated) {
                return SettingsAccess.SYSTEM;
            }
            throw new SettingsAccessDeniedException();
        }

        LudwigPrincipal principal = caller.get();
        if (isSelf(principal, subject.ref())) {
            return SettingsAccess.SELF;
        }
        if (isTrustedService(principal)) {
            return SettingsAccess.SYSTEM;
        }
        if (!hasAdminAuthority(principal)) {
            throw new SettingsAccessDeniedException();
        }
        // The authority says "may administer settings"; it does not say "in every organization".
        if (!subject.tenantId().equals(principal.tenantId())) {
            log.warn("Refusing cross-tenant settings access by {} (tenant {}) into tenant {}",
                    principal.subject(), principal.tenantId(), subject.tenantId());
            throw new SettingsAccessDeniedException();
        }
        return SettingsAccess.ADMIN;
    }

    /**
     * Requires the administrative authority outright - for writes at a role, tenant or platform
     * scope, which are never self-service however the subject is named.
     */
    public void requireAdmin(SettingsSubject subject) {
        Optional<LudwigPrincipal> caller = SecurityPrincipals.current();
        if (caller.isEmpty()) {
            if (allowUnauthenticated) {
                return;
            }
            throw new SettingsAccessDeniedException();
        }
        LudwigPrincipal principal = caller.get();
        if (isTrustedService(principal)) {
            return;
        }
        if (!hasAdminAuthority(principal) || !subject.tenantId().equals(principal.tenantId())) {
            throw new SettingsAccessDeniedException();
        }
    }

    /**
     * A peer workload rather than a person.
     *
     * <p>Deliberately not tenant-checked. A service is generally deployed once for the whole estate
     * and carries no tenant of its own, so requiring one to match would refuse every peer call in a
     * multi-tenant deployment - which is the opposite of what the flag is for. The tenant a peer's
     * lookup is confined to still comes from the {@code SettingsSubject} it names, which is how such
     * code is required to call in the first place.
     */
    private boolean isTrustedService(LudwigPrincipal principal) {
        return allowServicePrincipals && principal.type() == PrincipalType.SERVICE;
    }

    /**
     * Subject <em>and</em> type, because the two namespaces are independent: a partner id that
     * happens to equal a user's {@code sub} must not let one read the other's settings. The same
     * reasoning is why {@code PrincipalRef} carries a type at all.
     */
    private boolean isSelf(LudwigPrincipal principal, PrincipalRef ref) {
        return principal.type() == ref.type() && principal.subject().equals(ref.subject());
    }

    private boolean hasAdminAuthority(LudwigPrincipal principal) {
        return principal.hasRole(adminRole) || principal.hasPermission(adminAuthority);
    }
}
