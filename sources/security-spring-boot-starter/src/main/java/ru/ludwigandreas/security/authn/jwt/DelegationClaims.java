package ru.ludwigandreas.security.authn.jwt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ru.ludwigandreas.security.principal.Delegation;

/**
 * Reads an RFC 8693 delegation claim: an object whose {@code sub} is the acting party, optionally nesting the party
 * that acted for it as another {@code act}.
 *
 * <p>An absent claim is no delegation. A present one that cannot be read is an {@link IllegalArgumentException},
 * which the converter turns into a refused token - the same rule as {@code ludwig_pat}: present but unreadable is
 * rejected, not ignored. Only {@code sub} is read; anything else in the claim, {@code roles} included, is not.
 */
public final class DelegationClaims {

    /** The nested claim name, fixed by RFC 8693 whatever the outer claim is called. */
    public static final String NESTED = "act";

    /** Deepest chain read; deeper nesting is refused rather than walked without bound. */
    public static final int MAX_DEPTH = 5;

    private DelegationClaims() {
    }

    /**
     * The delegation a claim value describes.
     *
     * @param value the claim's value, or null when the token does not carry it
     * @return the delegation, or empty when there is no claim
     * @throws IllegalArgumentException when the claim is present but not an object with a string {@code sub} at
     *                                  every level, or nests deeper than {@link #MAX_DEPTH}
     */
    public static Optional<Delegation> read(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        List<String> chain = new ArrayList<>();
        Object level = value;
        while (level != null) {
            if (chain.size() == MAX_DEPTH) {
                throw new IllegalArgumentException("delegation nests deeper than " + MAX_DEPTH);
            }
            if (!(level instanceof Map<?, ?> claim) || !(claim.get("sub") instanceof String sub) || sub.isBlank()) {
                throw new IllegalArgumentException("delegation is not an object with a 'sub'");
            }
            chain.add(sub);
            level = claim.get(NESTED);
        }
        return Optional.of(new Delegation(chain));
    }
}
