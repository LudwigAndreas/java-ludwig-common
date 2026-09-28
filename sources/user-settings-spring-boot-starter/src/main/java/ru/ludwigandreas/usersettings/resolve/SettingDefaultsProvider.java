package ru.ludwigandreas.usersettings.resolve;

import java.util.Map;

/**
 * The configured tenant and platform defaults, as raw encoded text.
 *
 * <p>An interface rather than a direct read of the properties bean so that the hot-reloading
 * implementation can be swapped in without the value source knowing there is such a thing as a
 * reload. The values are in the same encoding a converter produces - {@code Europe/Moscow},
 * {@code true}, {@code PT30M} - because they are read back by the same converter, and a second
 * parsing path for configured values would be a second place for the two to disagree about what
 * {@code 1} means.
 */
public interface SettingDefaultsProvider {

    /** Setting key to encoded value, for the whole deployment. */
    Map<String, String> platformDefaults();

    /** Setting key to encoded value, for one tenant. Empty when that tenant configures none. */
    Map<String, String> tenantDefaults(String tenantId);
}
