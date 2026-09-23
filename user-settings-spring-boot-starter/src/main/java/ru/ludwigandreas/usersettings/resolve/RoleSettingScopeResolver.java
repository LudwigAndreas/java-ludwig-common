package ru.ludwigandreas.usersettings.resolve;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import ru.ludwigandreas.security.authz.AuthorityLookup;
import ru.ludwigandreas.usersettings.api.SettingLayer;
import ru.ludwigandreas.usersettings.api.SettingScope;
import ru.ludwigandreas.usersettings.api.SettingScopeResolver;
import ru.ludwigandreas.usersettings.api.SettingsSubject;

/**
 * The roles a subject holds, as the security module's own {@link AuthorityLookup} reports them.
 *
 * <p>Reusing that lookup rather than reading a role table directly is what keeps this resolver
 * working in a service whose roles come from anywhere - the identity projection, a token claim, a
 * service's own store - and means role resolution here is served by the same short-lived authority
 * cache the request path already populated, so the settings layer adds no extra query on a warm
 * request.
 *
 * <p><b>Order is the lookup's order, preserved.</b> {@code Authorities.roles()} is a
 * {@code LinkedHashSet} and keeps whatever order the resolver produced; a user in three roles that
 * each set the same key takes the first one's value. Sorting alphabetically here would be a policy
 * decision dressed up as tidiness - "ADMIN beats EDITOR because A comes first" is not a rule anybody
 * would write down on purpose.
 */
@RequiredArgsConstructor
public class RoleSettingScopeResolver implements SettingScopeResolver {

    private final AuthorityLookup authorities;

    @Override
    public SettingLayer layer() {
        return SettingLayer.ROLE;
    }

    @Override
    public List<SettingScope> scopesFor(SettingsSubject subject) {
        List<SettingScope> scopes = new ArrayList<>();
        for (String role : authorities.lookup(subject.ref()).roles()) {
            scopes.add(SettingScope.role(role));
        }
        return scopes;
    }
}
