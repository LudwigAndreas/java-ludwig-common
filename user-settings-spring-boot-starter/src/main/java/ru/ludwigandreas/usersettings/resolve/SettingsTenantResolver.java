package ru.ludwigandreas.usersettings.resolve;

import java.util.Optional;
import ru.ludwigandreas.security.authz.PrincipalRef;

/**
 * Decides which tenant a lookup by {@link PrincipalRef} is confined to.
 *
 * <p>Separate from the security module's principal because the question is different. Security asks
 * "who is calling"; this asks "which tenant's rows may this resolution touch", and the honest answer
 * is <em>the caller's</em> - not the target subject's. An administrator reading another user's
 * settings is scoped to their own tenant, so a cross-tenant read finds nothing rather than finding
 * data and then being refused, and there is no window in which the wrong rows were fetched.
 *
 * <p>Returning empty is a supported answer and means "refuse the lookup". It is not an invitation to
 * fall back to something.
 */
@FunctionalInterface
public interface SettingsTenantResolver {

    Optional<String> resolve(PrincipalRef ref);
}
