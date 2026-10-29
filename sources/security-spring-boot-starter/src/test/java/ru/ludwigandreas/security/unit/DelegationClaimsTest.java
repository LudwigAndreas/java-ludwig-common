package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.security.authn.jwt.DelegationClaims;

class DelegationClaimsTest {

    @Test
    @DisplayName("an absent claim is no delegation")
    void absent() {
        assertThat(DelegationClaims.read(null)).isEmpty();
    }

    @Test
    @DisplayName("act.sub is the acting party, and nested act claims follow it in order")
    void nested() {
        var delegation = DelegationClaims.read(Map.of("sub", "admin-console", "act", Map.of("sub", "gateway")));

        assertThat(delegation).get().satisfies(each -> {
            assertThat(each.actingParty()).isEqualTo("admin-console");
            assertThat(each.chain()).containsExactly("admin-console", "gateway");
        });
    }

    @Test
    @DisplayName("five levels are read, a sixth is refused")
    void depth() {
        assertThat(DelegationClaims.read(chain(DelegationClaims.MAX_DEPTH))).get()
                .satisfies(each -> assertThat(each.chain()).hasSize(DelegationClaims.MAX_DEPTH));
        assertThatThrownBy(() -> DelegationClaims.read(chain(DelegationClaims.MAX_DEPTH + 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deeper");
    }

    @Test
    @DisplayName("a string, an object without sub, a blank sub or an unreadable nested level is refused")
    void malformed() {
        assertThatThrownBy(() -> DelegationClaims.read("admin-console")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DelegationClaims.read(Map.of("client_id", "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DelegationClaims.read(Map.of("sub", " ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DelegationClaims.read(Map.of("sub", "a", "act", "b")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Object chain(int depth) {
        Object level = Map.of("sub", "party-" + depth);
        for (int i = depth - 1; i > 0; i--) {
            level = Map.of("sub", "party-" + i, "act", level);
        }
        return level;
    }
}
