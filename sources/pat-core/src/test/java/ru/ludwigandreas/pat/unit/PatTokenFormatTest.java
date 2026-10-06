package ru.ludwigandreas.pat.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.pat.token.PatDigest;
import ru.ludwigandreas.pat.token.PatTokens;

/** The wire format: round-trip, and every way a presented value can fail to be a token. */
class PatTokenFormatTest {

    @Test
    @DisplayName("a minted token parses back to its own key id and digest")
    void roundTrips() {
        PatTokens.MintedToken minted = PatTokens.mint();

        Optional<PatTokens.ParsedToken> parsed = PatTokens.parse(minted.rendered());

        assertThat(parsed).isPresent();
        assertThat(parsed.get().keyId()).isEqualTo(minted.keyId());
        assertThat(parsed.get().digest()).isEqualTo(minted.digest());
    }

    @Test
    @DisplayName("the rendered form carries the scannable prefix and four parts")
    void rendersTheDocumentedShape() {
        String rendered = PatTokens.mint().rendered();

        assertThat(rendered).startsWith(PatTokens.PREFIX + PatTokens.SEPARATOR);
        assertThat(rendered.split("_")).hasSize(4);
    }

    @Test
    @DisplayName("a tampered checksum is refused, which is what lets a typo be rejected without a query")
    void refusesABadChecksum() {
        String rendered = PatTokens.mint().rendered();
        String tampered = rendered.substring(0, rendered.lastIndexOf('_') + 1) + "deadbeef";

        assertThat(PatTokens.parse(tampered)).isEmpty();
    }

    @Test
    @DisplayName("a tampered secret is refused by the checksum before any digest is computed")
    void refusesATamperedSecret() {
        PatTokens.MintedToken minted = PatTokens.mint();
        String[] parts = minted.rendered().split("_");
        String tampered = String.join("_", parts[0], parts[1], parts[2].substring(1) + "Z", parts[3]);

        assertThat(PatTokens.parse(tampered)).isEmpty();
    }

    @Test
    @DisplayName("the wrong prefix, the wrong arity, empty parts, null and blank are all refused")
    void refusesMalformedInput() {
        PatTokens.MintedToken minted = PatTokens.mint();

        assertThat(PatTokens.parse(null)).isEmpty();
        assertThat(PatTokens.parse("")).isEmpty();
        assertThat(PatTokens.parse("   ")).isEmpty();
        assertThat(PatTokens.parse("ghp_" + minted.rendered().substring(5))).isEmpty();
        assertThat(PatTokens.parse("lpat_only_three")).isEmpty();
        assertThat(PatTokens.parse(minted.rendered() + "_extra")).isEmpty();
        assertThat(PatTokens.parse("lpat__secret_0")).isEmpty();
    }

    @Test
    @DisplayName("a truncated token is refused, which is the common real-world corruption")
    void refusesTruncation() {
        String rendered = PatTokens.mint().rendered();

        for (int cut = 1; cut < 12; cut++) {
            assertThat(PatTokens.parse(rendered.substring(0, rendered.length() - cut)))
                    .as("truncated by %d", cut)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("minting is not repeatable - distinct key ids and distinct secrets every time")
    void mintsDistinctTokens() {
        Set<String> keyIds = new HashSet<>();
        Set<String> digests = new HashSet<>();

        for (int i = 0; i < 500; i++) {
            PatTokens.MintedToken minted = PatTokens.mint();
            keyIds.add(minted.keyId());
            digests.add(minted.digest());
        }

        // A generator that repeats would be the single worst defect available in this module, and it is the
        // kind that a handful of ad-hoc checks miss. 500 draws over a 64-bit key id collide with probability
        // around 1e-14, so a failure here is a broken generator rather than bad luck.
        assertThat(keyIds).hasSize(500);
        assertThat(digests).hasSize(500);
    }

    @Test
    @DisplayName("the minted token's own toString hides the secret, which a record would have printed")
    void mintedTokenDoesNotPrintTheSecret() {
        PatTokens.MintedToken minted = PatTokens.mint();

        assertThat(minted.toString())
                .contains(minted.keyId())
                .doesNotContain(minted.secret().reveal())
                .doesNotContain(minted.rendered());
    }

    @Test
    @DisplayName("the digest of the parsed secret equals the digest stored at mint time")
    void digestIsStable() {
        PatTokens.MintedToken minted = PatTokens.mint();

        assertThat(PatDigest.of(minted.secret())).isEqualTo(minted.digest());
        assertThat(PatDigest.matches(minted.digest(), minted.digest())).isTrue();
    }
}
