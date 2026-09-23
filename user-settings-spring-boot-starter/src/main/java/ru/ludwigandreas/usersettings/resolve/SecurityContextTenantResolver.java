package ru.ludwigandreas.usersettings.resolve;

import java.util.Optional;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.security.principal.SecurityPrincipals;

/**
 * Takes the tenant from the authenticated caller, falling back to a configured single-tenant value.
 *
 * <p>The fallback exists because most deployments are not multi-tenant, and requiring every one of
 * them to thread a tenant through would make the common case worse to serve the uncommon one. It is
 * a configured constant ({@code ludwig.user-settings.default-tenant}, default {@code default}) and
 * never a guess: in a multi-tenant deployment an operator sets it to nothing, and a caller with no
 * tenant then gets a refused lookup instead of everybody's rows under one shared key.
 *
 * <p>Code running outside a request has no principal here and lands on the fallback. That is correct
 * for a single-tenant deployment and wrong for a multi-tenant one, which is why such code is
 * expected to use the {@code SettingsSubject} overloads and name the tenant it already knows - see
 * {@code SettingsLookup}.
 */
public class SecurityContextTenantResolver implements SettingsTenantResolver {

    private final String defaultTenant;

    public SecurityContextTenantResolver(String defaultTenant) {
        this.defaultTenant = defaultTenant == null || defaultTenant.isBlank() ? null : defaultTenant;
    }

    @Override
    public Optional<String> resolve(PrincipalRef ref) {
        return SecurityPrincipals.current()
                .flatMap(principal -> principal.tenant())
                .or(() -> Optional.ofNullable(defaultTenant));
    }
}
