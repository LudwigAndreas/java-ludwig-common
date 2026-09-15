package ru.ludwigandreas.security.data;

import java.io.Serializable;
import java.util.Objects;

/**
 * One axis a row can be restricted along.
 *
 * <p>A value type with well-known constants rather than an enum, so a service can introduce its own
 * axis ({@code ScopeDimension.of("region")}) without this module needing to know about it. The three
 * constants cover what almost every deployment needs and are what the configuration-driven
 * {@link PolicyDataScopeProvider} understands.
 *
 * @param name lower-case, stable identifier; it appears in configuration keys and audit records
 */
public record ScopeDimension(String name) implements Serializable, Comparable<ScopeDimension> {

    /** The row's creator - "show me only the orders I placed". */
    public static final ScopeDimension OWNER = new ScopeDimension("owner");

    /** The owning organization in a multi-tenant deployment. */
    public static final ScopeDimension TENANT = new ScopeDimension("tenant");

    /** The external partner a row belongs to - "this partner sees only rows filed under its id". */
    public static final ScopeDimension PARTNER = new ScopeDimension("partner");

    public ScopeDimension {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("scope dimension name must not be blank");
        }
        name = name.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** The axes {@link PolicyDataScopeProvider} understands out of the box, and the ones a strict
     *  policy is restricted to. Anything else is a custom axis and has to be opted into. */
    public static final java.util.Set<ScopeDimension> BUILT_IN = java.util.Set.of(OWNER, TENANT, PARTNER);

    public static ScopeDimension of(String name) {
        return new ScopeDimension(name);
    }

    @Override
    public int compareTo(ScopeDimension other) {
        return name.compareTo(other.name);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ScopeDimension dimension && Objects.equals(name, dimension.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}
