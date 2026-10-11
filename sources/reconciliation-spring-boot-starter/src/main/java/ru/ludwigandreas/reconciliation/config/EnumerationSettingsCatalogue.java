package ru.ludwigandreas.reconciliation.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every enumeration's resolved settings, by name.
 *
 * <p>A type of its own rather than a bare {@code Map<String, EnumerationSettings>} bean. Spring reads
 * an injection point of type {@code Map<String, X>} as "every bean of type X, by bean name" before it
 * considers a bean that is itself such a map, so a second map-typed settings bean beside the task
 * settings would be one refactoring away from being injected as the wrong thing.
 *
 * @param byName the settings, keyed by enumeration name, in declaration order
 */
public record EnumerationSettingsCatalogue(Map<String, EnumerationSettings> byName) {

    /** Defensive copy that keeps declaration order. */
    public EnumerationSettingsCatalogue {
        byName = Collections.unmodifiableMap(new LinkedHashMap<>(byName));
    }
}
