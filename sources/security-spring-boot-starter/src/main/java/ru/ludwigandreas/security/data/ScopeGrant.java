package ru.ludwigandreas.security.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;

/**
 * One configured grant - the right-hand side of {@code ROLE_ORDER_AGENT: OWN+TENANT} - after parsing.
 *
 * <p>Parsed once, at startup, rather than on every request. That buys two things, and the second is the
 * one that matters: a typo in a policy (`OWNN`, `PARNTER`) becomes a startup failure on deploy instead
 * of a {@code SecurityConfigurationException} thrown the first time someone happens to call the one
 * endpoint that exercises that role - which in practice means at 3am, from a caller who then gets a 500
 * where they should have got data or a clean 403.
 *
 * @param kind       whether this grant is unrestricted, empty, or a conjunction of dimensions
 * @param dimensions the AND-ed dimensions for {@link Kind#RESTRICTED}; empty otherwise
 */
public record ScopeGrant(Kind kind, List<ScopeDimension> dimensions) {

    private static final String ALL_TOKEN = "ALL";
    private static final String NONE_TOKEN = "NONE";
    private static final String OWN_TOKEN = "OWN";

    private static final ScopeGrant ALL = new ScopeGrant(Kind.ALL, List.of());
    private static final ScopeGrant NONE = new ScopeGrant(Kind.NONE, List.of());

    public enum Kind {
        ALL,
        NONE,
        RESTRICTED
    }

    public ScopeGrant {
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
    }

    public static ScopeGrant all() {
        return ALL;
    }

    public static ScopeGrant none() {
        return NONE;
    }

    /**
     * @param strictTokens reject anything other than {@code ALL}/{@code NONE}/{@code OWN}/{@code TENANT}/
     *                     {@code PARTNER}. On by default: a custom axis is a deliberate choice, and
     *                     without this a misspelled built-in silently becomes a custom dimension that
     *                     nothing ever grants - which reads as "this role has no access" and gets
     *                     "fixed" by widening the policy.
     * @param context      where the grant came from, quoted back in the error ({@code order.read.ROLE_X})
     */
    public static ScopeGrant parse(String raw, boolean strictTokens, String context) {
        String normalized = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty() || NONE_TOKEN.equals(normalized)) {
            return NONE;
        }
        if (ALL_TOKEN.equals(normalized)) {
            return ALL;
        }

        List<ScopeDimension> dimensions = new ArrayList<>(2);
        for (String token : normalized.split("\\+")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (ALL_TOKEN.equals(trimmed) || NONE_TOKEN.equals(trimmed)) {
                throw new SecurityConfigurationException("Policy " + context + " combines '" + trimmed
                        + "' with other tokens in '" + raw + "'. ALL and NONE stand alone: ALL means no "
                        + "restriction at all, so ANDing it with one is contradictory.");
            }
            ScopeDimension dimension = dimensionOf(trimmed, raw, strictTokens, context);
            if (!dimensions.contains(dimension)) {
                dimensions.add(dimension);
            }
        }
        return dimensions.isEmpty() ? NONE : new ScopeGrant(Kind.RESTRICTED, dimensions);
    }

    private static ScopeDimension dimensionOf(String token, String raw, boolean strictTokens, String context) {
        if (OWN_TOKEN.equals(token)) {
            return ScopeDimension.OWNER;
        }
        ScopeDimension dimension = ScopeDimension.of(token);
        if (strictTokens && !ScopeDimension.BUILT_IN.contains(dimension)) {
            throw new SecurityConfigurationException("Unknown scope token '" + token + "' in policy "
                    + context + " ('" + raw + "'). Use ALL, NONE, OWN, TENANT or PARTNER, optionally "
                    + "'+'-joined. To use a custom axis, bind it on the resource's DataScopeMapping and "
                    + "set ludwig.security.data.strict-policy-tokens=false.");
        }
        return dimension;
    }
}
