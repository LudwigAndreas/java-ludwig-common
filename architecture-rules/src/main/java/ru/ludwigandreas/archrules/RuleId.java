package ru.ludwigandreas.archrules;

import java.util.Objects;
import java.util.Optional;

/**
 * The stable identity of a single rule, e.g. {@code persistence.entities-reside-in-entity-packages}
 * or, for a rule instantiated once per module, {@code cycles.module-internals[com.acme.orders]}.
 *
 * <p>Ids are the contract between this library and a consuming service's configuration: they are
 * what a service names to switch one rule off, and what appears as the test name in the build
 * report. They are therefore treated as API and only change on a major version.
 *
 * @param group     the family this rule belongs to
 * @param name      kebab-case name, unique within the group
 * @param qualifier optional instance discriminator for rules generated per module, may be null
 */
public record RuleId(RuleGroup group, String name, String qualifier) {

    private static final String WILDCARD = "*";

    public RuleId {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Rule name must not be blank");
        }
        if (qualifier != null && qualifier.isBlank()) {
            throw new IllegalArgumentException("Rule qualifier must not be blank when present");
        }
    }

    public static RuleId of(RuleGroup group, String name) {
        return new RuleId(group, name, null);
    }

    /** An instance of a rule that exists once per module, discriminated by the module package. */
    public static RuleId of(RuleGroup group, String name, String qualifier) {
        return new RuleId(group, name, qualifier);
    }

    /** {@code group.name}, without the qualifier - the selector that addresses every instance. */
    public String baseId() {
        return group.id() + "." + name;
    }

    /** The full id including the qualifier, as printed in reports. */
    public String value() {
        return qualifier == null ? baseId() : baseId() + "[" + qualifier + "]";
    }

    public Optional<String> optionalQualifier() {
        return Optional.ofNullable(qualifier);
    }

    /**
     * How specifically the given selector addresses this rule, or {@code -1} when it does not match
     * at all. Higher wins, so {@code persistence.entities-reside-in-entity-packages} beats
     * {@code persistence}, which beats {@code *}.
     */
    public int specificityOf(String selector) {
        String normalized = Objects.requireNonNull(selector, "selector").trim();
        if (WILDCARD.equals(normalized)) {
            return 0;
        }
        if (normalized.equals(group.id()) || normalized.equals(group.id() + ".*")) {
            return 1;
        }
        if (normalized.equals(baseId())) {
            return 2;
        }
        if (normalized.equals(value())) {
            return 3;
        }
        return -1;
    }

    @Override
    public String toString() {
        return value();
    }
}
