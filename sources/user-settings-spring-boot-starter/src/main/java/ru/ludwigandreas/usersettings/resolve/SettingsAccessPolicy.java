package ru.ludwigandreas.usersettings.resolve;

import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.security.admin.AdminInvocation;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;
import ru.ludwigandreas.security.principal.SecurityPrincipals;
import ru.ludwigandreas.usersettings.api.SettingsSubject;
import ru.ludwigandreas.usersettings.admin.SettingsAdminOperations;
import ru.ludwigandreas.usersettings.exception.SettingsAccessDeniedException;
import ru.ludwigandreas.usersettings.exception.SettingsSubjectNotFoundException;

/**
 * "You may read and write your own settings; somebody else's needs an administrative operation of the
 * {@code settings} area."
 *
 * <p>Enforced here, once, rather than in each caller. The rule is the same in every service that
 * uses this module, which is exactly why leaving it to callers does not work: it gets reimplemented
 * per endpoint, and the endpoint that gets it wrong is the one that takes a subject id from the path
 * and passes it through without looking at it.
 *
 * <p>Which callers may administer settings is not decided here: the administrative surface guard has
 * already checked the service's {@code ludwig.admin.grants} before a {@code settings.*} handler runs,
 * and this policy only recognises that such a handler is running. What it still decides is the tenant:
 * the administrator's own tenant must match the tenant the lookup is confined to, or an administrator
 * of one organization could read another organization's users simply by knowing a subject id. That
 * refusal is a {@code 404}, as the administrative surface answers for another tenant's resource.
 */
@Slf4j
public class SettingsAccessPolicy {

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
     * peers held to the administrative surface anyway sets this false and grants them a
     * {@code settings} operation.
     */
    private final boolean allowServicePrincipals;

    private final Supplier<Optional<AdminInvocation>> invocation;

    /**
     * Builds the policy.
     *
     * @param allowUnauthenticated   whether in-process code with no principal is a system caller
     * @param allowServicePrincipals whether a peer service is a system caller
     * @param invocation             the administrative operation the current request performs;
     *                               {@code AdminInvocation::current} outside tests
     */
    public SettingsAccessPolicy(boolean allowUnauthenticated, boolean allowServicePrincipals,
                                Supplier<Optional<AdminInvocation>> invocation) {
        this.allowUnauthenticated = allowUnauthenticated;
        this.allowServicePrincipals = allowServicePrincipals;
        this.invocation = invocation;
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
        requireAdministrativeAccess(principal, subject);
        return SettingsAccess.ADMIN;
    }

    /**
     * Requires an administrative operation outright - for writes at a role, tenant or platform
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
        requireAdministrativeAccess(principal, subject);
    }

    /**
     * Outside a {@code settings} handler, a {@code 403} as self-service has always answered. Inside
     * one, the grant has been checked and only the tenant is left: the operation says "may administer
     * settings"; it does not say "in every organization".
     */
    private void requireAdministrativeAccess(LudwigPrincipal principal, SettingsSubject subject) {
        boolean administrative = invocation.get()
                .filter(current -> SettingsAdminOperations.AREA.equals(current.operation().area()))
                .isPresent();
        if (!administrative) {
            throw new SettingsAccessDeniedException();
        }
        if (!subject.tenantId().equals(principal.tenantId())) {
            log.warn("Refusing cross-tenant settings access by {} (tenant {}) into tenant {}",
                    principal.subject(), principal.tenantId(), subject.tenantId());
            throw new SettingsSubjectNotFoundException();
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
}
