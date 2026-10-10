package ru.ludwigandreas.testsupport.local.fixture;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * What the local application does with seed data when it starts.
 *
 * <p>Selected by {@value #PROPERTY}. The mode is explicit because the database usually outlives the
 * run: with Testcontainers reuse switched on, the rows from yesterday's session are still there, and
 * "seed again" and "leave my data alone" are both things a developer reasonably means.
 */
public enum FixtureMode {

    /** Empty every table except the migration tool's, then seed. The default: every launch starts equal. */
    RESET,

    /** Seed only when the database holds no rows; otherwise touch nothing. */
    KEEP,

    /** Neither empty nor seed. */
    NONE;

    /** The property that selects the mode. */
    public static final String PROPERTY = "ludwig.local.fixtures.mode";

    /**
     * Resolves a configured mode.
     *
     * @param value the configured value, or {@code null} for the default
     * @return the mode
     * @throws IllegalArgumentException naming the valid values when {@code value} is not one of them
     */
    public static FixtureMode parse(String value) {
        if (value == null || value.isBlank()) {
            return RESET;
        }
        String wanted = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(mode -> mode.name().equals(wanted))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("'" + value + "' is not a fixture mode. "
                        + "Valid values for " + PROPERTY + ": " + Arrays.stream(values())
                        .map(mode -> mode.name().toLowerCase(Locale.ROOT))
                        .collect(Collectors.joining(", "))));
    }
}
