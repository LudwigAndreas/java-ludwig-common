package ru.ludwigandreas.usersettings.resolve;

import java.util.List;
import ru.ludwigandreas.usersettings.api.SettingLayer;
import ru.ludwigandreas.usersettings.api.SettingScope;
import ru.ludwigandreas.usersettings.api.SettingScopeResolver;
import ru.ludwigandreas.usersettings.api.SettingsSubject;

/** The subject's own scope. Always exactly one, and the most specific layer there is. */
public class UserSettingScopeResolver implements SettingScopeResolver {

    @Override
    public SettingLayer layer() {
        return SettingLayer.USER;
    }

    @Override
    public List<SettingScope> scopesFor(SettingsSubject subject) {
        return List.of(SettingScope.user(subject.subject()));
    }
}
