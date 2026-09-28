package ru.ludwigandreas.archrules;

import java.util.Locale;
import java.util.Optional;

/**
 * How a violation is treated by the build.
 *
 * <p>Severity is what makes a rule adoptable without either freezing it or switching it off: a team
 * can put a rule in at {@link #WARNING}, watch it in the reports while the debt is worked down, and
 * promote it to {@link #ERROR} once it is clean. Both levels appear in the JSON report, so an
 * organisation-wide dashboard can see the drift a service has accepted, not only what it enforces.
 */
public enum RuleSeverity {

    /** A violation fails the build. The default for every rule. */
    ERROR,

    /** A violation is reported in both reports but does not fail the build. */
    WARNING;

    /** Lower-case form used in the JSON report and in property values. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<RuleSeverity> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        for (RuleSeverity severity : values()) {
            if (severity.id().equalsIgnoreCase(id.trim())) {
                return Optional.of(severity);
            }
        }
        return Optional.empty();
    }
}
