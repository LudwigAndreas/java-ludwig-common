package ru.ludwigandreas.archrules.report;

import java.util.Locale;
import java.util.Optional;

/** Whether the console report is colourised. */
public enum ColorMode {

    /**
     * Colour unless the environment says otherwise: {@code NO_COLOR} set (the de-facto standard for
     * "this output is being captured"), or {@code TERM=dumb}.
     */
    AUTO,

    ALWAYS,

    NEVER;

    public boolean isEnabled() {
        return switch (this) {
            case ALWAYS -> true;
            case NEVER -> false;
            case AUTO -> System.getenv("NO_COLOR") == null && !"dumb".equals(System.getenv("TERM"));
        };
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<ColorMode> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        for (ColorMode mode : values()) {
            if (mode.id().equalsIgnoreCase(id.trim())) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
