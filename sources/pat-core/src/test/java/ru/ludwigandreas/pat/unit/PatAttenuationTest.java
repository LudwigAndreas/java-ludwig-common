package ru.ludwigandreas.pat.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.pat.scope.PatAttenuation;

/**
 * The attenuation invariant, which is the thing this whole capability exists to hold.
 *
 * <p>The last test in this class is the unusual one and the most important: it asserts that no method capable
 * of widening a scope set exists at all. Every other test here checks that the intersection behaves; that one
 * checks that the intersection is the only thing available.
 */
class PatAttenuationTest {

    @Test
    @DisplayName("the effective set is the owner's authorities narrowed by the token's scopes")
    void intersects() {
        PatAttenuation attenuation = PatAttenuation.of(List.of("ROLE_READER", "orders:read"));

        assertThat(attenuation.intersect(Set.of("ROLE_READER", "ROLE_ADMIN", "orders:read", "orders:write")))
                .containsExactlyInAnyOrder("ROLE_READER", "orders:read");
    }

    @Test
    @DisplayName("a demoted owner loses the authority, with no revocation and no issuer involved")
    void demotedOwnerLosesTheAuthority() {
        PatAttenuation attenuation = PatAttenuation.of(List.of("ROLE_ADMIN"));

        // Before: the owner holds it, so the token confers it.
        assertThat(attenuation.intersect(Set.of("ROLE_ADMIN", "ROLE_READER"))).containsExactly("ROLE_ADMIN");

        // After the demotion: the token is unchanged and confers nothing. This is the whole security
        // argument of the capability, in two assertions.
        assertThat(attenuation.intersect(Set.of("ROLE_READER"))).isEmpty();
    }

    @Test
    @DisplayName("a scope naming an authority the owner never held grants nothing - inert, not privileged")
    void scopeForAnAbsentAuthorityGrantsNothing() {
        assertThat(PatAttenuation.of(List.of("ROLE_SUPERUSER")).intersect(Set.of("ROLE_READER"))).isEmpty();
    }

    @Test
    @DisplayName("an authority granted after issuance becomes effective without reissuing the token")
    void ownerGainingAnAuthorityTakesEffect() {
        PatAttenuation attenuation = PatAttenuation.of(List.of("orders:write"));

        assertThat(attenuation.intersect(Set.of("orders:read"))).isEmpty();
        assertThat(attenuation.intersect(Set.of("orders:read", "orders:write")))
                .containsExactly("orders:write");
    }

    @Test
    @DisplayName("a disabled owner with no authorities confers none, which is the owner-disabled case")
    void emptyOwnerSetYieldsNothing() {
        PatAttenuation attenuation = PatAttenuation.of(List.of("ROLE_ADMIN"));

        assertThat(attenuation.intersect(Set.of())).isEmpty();
        assertThat(attenuation.intersect(null)).isEmpty();
    }

    @Test
    @DisplayName("the result is never larger than the owner's set, over a wide range of combinations")
    void neverExceedsTheOwner() {
        List<String> universe = List.of("a", "b", "c", "d");

        for (int scopeBits = 1; scopeBits < 16; scopeBits++) {
            for (int ownerBits = 0; ownerBits < 16; ownerBits++) {
                Set<String> scopes = subset(universe, scopeBits);
                Set<String> owner = subset(universe, ownerBits);
                Set<String> effective = PatAttenuation.of(scopes).intersect(owner);

                // The property, stated directly rather than through examples: the result is a subset of
                // both sides. If a union were ever reachable, one of these 240 combinations would show it.
                assertThat(owner).containsAll(effective);
                assertThat(scopes).containsAll(effective);
            }
        }
    }

    @Test
    @DisplayName("an empty scope set is refused - a token that attenuates to nothing is a mistake")
    void emptyScopeSetIsRefused() {
        assertThatThrownBy(() -> PatAttenuation.of(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one scope");
        assertThatThrownBy(() -> PatAttenuation.of(List.of("", "   ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PatAttenuation.of(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the declared scope set cannot be mutated through the accessor")
    void scopesAreUnmodifiable() {
        Set<String> scopes = PatAttenuation.of(List.of("a")).scopes();

        assertThatThrownBy(() -> scopes.add("b")).isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * No method on this type can widen a scope set.
     *
     * <p>Written reflectively, which is unusual for a unit test and is justified here. The invariant is not
     * "the intersection is correct" - the tests above cover that - it is "intersection is the only operation
     * offered". That is a statement about the type's surface, and the failure it guards against is somebody
     * adding a reasonable-looking {@code withAdditionalScope} in six months to solve a real problem, which no
     * behavioural test would ever fail on.
     *
     * <p>{@code architecture-rules} cannot express this: it is about the absence of a method on one class
     * rather than about a dependency or a package boundary, and a rule keyed on method names across the
     * platform would flag every legitimate {@code plus} and {@code union} in it.
     */
    @Test
    @DisplayName("no widening operation exists on the type at all")
    void noWideningOperationExists() {
        List<String> widening = List.of("union", "plus", "add", "grant", "with", "merge", "combine", "or");

        List<String> found = java.util.Arrays.stream(PatAttenuation.class.getDeclaredMethods())
                .filter(method -> method.getReturnType() == PatAttenuation.class
                        || method.getReturnType() == Set.class)
                .map(Method::getName)
                .filter(name -> widening.stream().anyMatch(name.toLowerCase(java.util.Locale.ROOT)::startsWith))
                .toList();

        assertThat(found)
                .as("a scope set that can grow has stopped being an attenuation")
                .isEmpty();
    }

    private static Set<String> subset(List<String> universe, int bits) {
        Set<String> subset = new java.util.LinkedHashSet<>();
        for (int i = 0; i < universe.size(); i++) {
            if ((bits & (1 << i)) != 0) {
                subset.add(universe.get(i));
            }
        }
        return subset;
    }
}
