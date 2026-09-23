package ru.ludwigandreas.usersettings.resolve;

import java.util.List;
import ru.ludwigandreas.usersettings.api.SettingLayer;
import ru.ludwigandreas.usersettings.api.SettingScope;
import ru.ludwigandreas.usersettings.api.SettingScopeResolver;
import ru.ludwigandreas.usersettings.api.SettingsSubject;

/**
 * The deployment-wide scope, which is the same for everyone.
 *
 * <p>Supplied by configuration rather than by rows, which is what lets a platform default change
 * without a redeploy and without a migration - see {@code ConfiguredSettingValueSource}. It is also
 * why there is no tenant column to get wrong here: this layer is outside tenancy by definition, and
 * anything that needs to differ per tenant belongs one layer down.
 */
public class PlatformSettingScopeResolver implements SettingScopeResolver {

    @Override
    public SettingLayer layer() {
        return SettingLayer.PLATFORM;
    }

    @Override
    public List<SettingScope> scopesFor(SettingsSubject subject) {
        return List.of(SettingScope.platform());
    }
}
