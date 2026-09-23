package ru.ludwigandreas.usersettings.resolve;

import java.util.List;
import ru.ludwigandreas.usersettings.api.SettingLayer;
import ru.ludwigandreas.usersettings.api.SettingScope;
import ru.ludwigandreas.usersettings.api.SettingScopeResolver;
import ru.ludwigandreas.usersettings.api.SettingsSubject;

/**
 * The tenant the lookup is confined to.
 *
 * <p>Takes the tenant from the {@link SettingsSubject} rather than looking one up. The subject's
 * tenant has already been decided, by the security context or by the caller, and deciding it a
 * second time here is how the two answers get to disagree - at which point a row could be read under
 * one tenant and the access check performed against another.
 */
public class TenantSettingScopeResolver implements SettingScopeResolver {

    @Override
    public SettingLayer layer() {
        return SettingLayer.TENANT;
    }

    @Override
    public List<SettingScope> scopesFor(SettingsSubject subject) {
        return List.of(SettingScope.tenant(subject.tenantId()));
    }
}
