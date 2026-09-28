package ru.ludwigandreas.hotreload.propertysource;

/**
 * Single place defining how a {@link ru.ludwigandreas.hotreload.core.ReloadableSource#id()} maps to the
 * name of its {@link ReloadablePropertySource} in the {@code Environment} - shared by {@link
 * ru.ludwigandreas.hotreload.config.HotReloadEnvironmentPostProcessor} (which creates the property
 * sources early) and {@link EnvironmentPropertySourceBridge} (which updates them at runtime), so the two
 * never drift apart.
 */
public final class HotReloadPropertySourceNames {

    private static final String PREFIX = "hotreload:";

    private HotReloadPropertySourceNames() {
    }

    public static String forSourceId(String sourceId) {
        return PREFIX + sourceId;
    }
}
