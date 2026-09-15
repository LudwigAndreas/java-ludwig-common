package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.security.data.ScopeDimension;
import ru.ludwigandreas.security.data.ScopeGrant;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;

class ScopeGrantTest {

    private ScopeGrant parse(String raw) {
        return ScopeGrant.parse(raw, true, "order.read.ROLE_X");
    }

    @Test
    void parsesTheStandaloneTokens() {
        assertThat(parse("ALL").kind()).isEqualTo(ScopeGrant.Kind.ALL);
        assertThat(parse("NONE").kind()).isEqualTo(ScopeGrant.Kind.NONE);
        assertThat(parse("").kind()).isEqualTo(ScopeGrant.Kind.NONE);
        assertThat(parse(null).kind()).isEqualTo(ScopeGrant.Kind.NONE);
    }

    @Test
    @DisplayName("OWN is the configuration spelling of the owner dimension")
    void parsesDimensions() {
        assertThat(parse("OWN").dimensions()).containsExactly(ScopeDimension.OWNER);
        assertThat(parse(" partner ").dimensions()).containsExactly(ScopeDimension.PARTNER);
        assertThat(parse("OWN+TENANT").dimensions())
                .containsExactly(ScopeDimension.OWNER, ScopeDimension.TENANT);
    }

    @Test
    @DisplayName("a typo becomes a startup failure, not a role that silently grants nothing")
    void rejectsUnknownTokensWhenStrict() {
        assertThatThrownBy(() -> parse("PARNTER"))
                .isInstanceOf(SecurityConfigurationException.class)
                .hasMessageContaining("PARNTER")
                .hasMessageContaining("order.read.ROLE_X");
    }

    @Test
    void allowsCustomAxesWhenNotStrict() {
        ScopeGrant grant = ScopeGrant.parse("region", false, "order.read.ROLE_X");

        assertThat(grant.dimensions()).containsExactly(ScopeDimension.of("region"));
    }

    @Test
    @DisplayName("ALL combined with a dimension is contradictory and is refused rather than guessed at")
    void rejectsAllCombinedWithADimension() {
        assertThatThrownBy(() -> parse("ALL+TENANT"))
                .isInstanceOf(SecurityConfigurationException.class)
                .hasMessageContaining("stand alone");
    }

    @Test
    void ignoresRepeatedAndEmptySegments() {
        assertThat(parse("OWN++OWN").dimensions()).containsExactly(ScopeDimension.OWNER);
    }
}
