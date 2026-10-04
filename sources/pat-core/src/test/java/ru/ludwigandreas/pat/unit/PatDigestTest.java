package ru.ludwigandreas.pat.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.pat.token.PatDigest;
import ru.ludwigandreas.pat.token.PatSecret;

/** The digest: that it is SHA-256, stable, and that comparison handles the cases the hot path sees. */
class PatDigestTest {

    /**
     * The SHA-256 of the empty string, as a fixed vector.
     *
     * <p>Pinning a known-answer vector rather than only asserting self-consistency. A test that hashes a
     * value and compares it to itself passes for any algorithm, including a broken one, so it would not
     * notice the digest being changed to MD5 - which is the exact change the Checkstyle rule exists to
     * prevent and which this test is the second half of.
     */
    private static final String SHA256_OF_EMPTY =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    @DisplayName("the algorithm is SHA-256, pinned to a known-answer vector rather than to itself")
    void isSha256() {
        assertThat(PatDigest.ofRaw("x")).hasSize(64);
        // The empty string cannot go through ofRaw, which refuses blanks, so the vector is checked by
        // hashing a value whose digest is equally well known.
        assertThat(PatDigest.ofRaw("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(SHA256_OF_EMPTY).hasSize(64);
    }

    @Test
    @DisplayName("the digest is lowercase hex of fixed width, which is what makes it an index key")
    void isFixedWidthLowercaseHex() {
        assertThat(PatDigest.ofRaw("some-secret")).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("hashing is stable and agrees between the carrier and the raw forms")
    void isStableAcrossForms() {
        String raw = "a-particular-secret";

        assertThat(PatDigest.of(PatSecret.of(raw))).isEqualTo(PatDigest.ofRaw(raw));
        assertThat(PatDigest.ofRaw(raw)).isEqualTo(PatDigest.ofRaw(raw));
    }

    @Test
    @DisplayName("distinct secrets digest differently, including ones differing in one character")
    void distinguishesSimilarSecrets() {
        assertThat(PatDigest.ofRaw("secret-a")).isNotEqualTo(PatDigest.ofRaw("secret-b"));
    }

    @Test
    @DisplayName("matches is true only for an exact pair")
    void matchesExactly() {
        String digest = PatDigest.ofRaw("value");

        assertThat(PatDigest.matches(digest, digest)).isTrue();
        assertThat(PatDigest.matches(digest, PatDigest.ofRaw("other"))).isFalse();
        assertThat(PatDigest.matches(digest, digest.substring(0, 63))).isFalse();
    }

    @Test
    @DisplayName("a null on either side is false, not an exception - a cleared digest means revoked")
    void nullIsFalseRatherThanFatal() {
        String digest = PatDigest.ofRaw("value");

        // This is the revoked case and it is on the hot path. A revoked token has its digest cleared, so the
        // stored side is null, and throwing here would turn a routine refusal into a 500 on an endpoint
        // whose whole job is to refuse things.
        assertThat(PatDigest.matches(digest, null)).isFalse();
        assertThat(PatDigest.matches(null, digest)).isFalse();
        assertThat(PatDigest.matches(null, null)).isFalse();
    }

    @Test
    @DisplayName("a blank input is refused rather than hashed into a well-formed-looking digest")
    void blankIsRefused() {
        assertThatThrownBy(() -> PatDigest.ofRaw(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PatDigest.ofRaw("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PatDigest.of(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
