package ru.ludwigandreas.pat.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.pat.token.PatSecret;

/** The secret carrier: that it will not print itself, and that it behaves sanely as a value. */
class PatSecretTest {

    private static final String RAW = "s3cret-value-that-must-never-be-printed";

    @Test
    @DisplayName("toString masks, and so does string interpolation, which is how it actually leaks")
    void toStringMasks() {
        PatSecret secret = PatSecret.of(RAW);

        assertThat(secret.toString()).doesNotContain(RAW);

        // The assertion that matters. Nobody writes log.info(secret) - they write a message that happens to
        // contain the object, or they let a record's generated toString print its components, and both of
        // those go through this path.
        assertThat("presented " + secret).doesNotContain(RAW);
        assertThat(String.format("presented %s", secret)).doesNotContain(RAW);
        assertThat(String.valueOf(secret)).doesNotContain(RAW);
    }

    @Test
    @DisplayName("reveal returns the secret, because issuance and verification have to read it")
    void revealReturnsTheSecret() {
        assertThat(PatSecret.of(RAW).reveal()).isEqualTo(RAW);
    }

    @Test
    @DisplayName("two carriers of the same secret are equal, which identity comparison would get wrong")
    void equalityComparesTheSecret() {
        assertThat(PatSecret.of(RAW)).isEqualTo(PatSecret.of(RAW));
        assertThat(PatSecret.of(RAW)).isNotEqualTo(PatSecret.of(RAW + "x"));
        assertThat(PatSecret.of(RAW)).isNotEqualTo(null);
        assertThat(PatSecret.of(RAW)).isNotEqualTo("not a secret");
    }

    @Test
    @DisplayName("hashCode does not depend on the secret, so the value never reaches a hash bucket")
    void hashCodeIsConstant() {
        assertThat(PatSecret.of(RAW).hashCode())
                .isEqualTo(PatSecret.of("something else entirely").hashCode());
    }

    @Test
    @DisplayName("a blank secret is refused at construction rather than stored and failing later")
    void blankSecretIsRefused() {
        assertThatThrownBy(() -> PatSecret.of(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PatSecret.of("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PatSecret.of("   ")).isInstanceOf(IllegalArgumentException.class);
    }
}
