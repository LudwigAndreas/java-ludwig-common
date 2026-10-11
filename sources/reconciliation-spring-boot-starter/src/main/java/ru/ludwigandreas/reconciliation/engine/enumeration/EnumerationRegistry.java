package ru.ludwigandreas.reconciliation.engine.enumeration;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Every enumeration in the context, by name. Immutable: enumerations are wired once, at startup. */
public class EnumerationRegistry {

    private final Map<String, RegisteredEnumeration<?, ?, ?, ?>> byName;

    /**
     * Creates the registry.
     *
     * @param enumerations the registered enumerations
     */
    public EnumerationRegistry(Collection<RegisteredEnumeration<?, ?, ?, ?>> enumerations) {
        Map<String, RegisteredEnumeration<?, ?, ?, ?>> indexed = new LinkedHashMap<>();
        enumerations.forEach(enumeration -> indexed.put(enumeration.name(), enumeration));
        this.byName = java.util.Collections.unmodifiableMap(indexed);
    }

    /**
     * Finds an enumeration.
     *
     * @param name its name
     * @return the enumeration, or empty
     */
    public Optional<RegisteredEnumeration<?, ?, ?, ?>> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** Every enumeration, in declaration order. */
    public List<RegisteredEnumeration<?, ?, ?, ?>> all() {
        return List.copyOf(byName.values());
    }

    /** Every enumeration's name, in declaration order. */
    public List<String> names() {
        return List.copyOf(byName.keySet());
    }
}
