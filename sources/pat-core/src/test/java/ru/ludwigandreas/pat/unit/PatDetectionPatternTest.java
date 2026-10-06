package ru.ludwigandreas.pat.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.pat.token.PatDetection;
import ru.ludwigandreas.pat.token.PatTokens;

/**
 * The published detection pattern, checked against tokens this module actually mints.
 *
 * <p>This is the only thing standing between a published regex and a published regex that is wrong, and a
 * scanner rule that matches nothing fails silently - it runs, it reports no findings, and the absence of
 * findings reads as good news. A pattern checked only by eye is a pattern nobody has checked.
 */
class PatDetectionPatternTest {

    @Test
    @DisplayName("the published pattern matches every token the minter produces")
    void matchesMintedTokens() {
        Pattern pattern = PatDetection.pattern();

        // Many draws, not one. The checksum is Long.toHexString of a CRC32C, which does not pad - so a CRC
        // with leading zero bytes renders shorter, and a pattern with an exact width would match most
        // tokens and miss a predictable fraction. One sample would very likely not find that.
        for (int i = 0; i < 2000; i++) {
            String rendered = PatTokens.mint().rendered();
            assertThat(pattern.matcher(rendered).find())
                    .as("published pattern must match %s", rendered)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("the pattern finds a token embedded in a config file or a commit, not just standalone")
    void findsTokensInContext() {
        Pattern pattern = PatDetection.pattern();
        String rendered = PatTokens.mint().rendered();

        assertThat(pattern.matcher("LUDWIG_TOKEN=" + rendered).find()).isTrue();
        assertThat(pattern.matcher("curl -H \"Authorization: Bearer " + rendered + "\" https://x").find())
                .isTrue();
        assertThat(pattern.matcher("  token: " + rendered + "  # do not commit").find()).isTrue();
    }

    @Test
    @DisplayName("the pattern does not match an arbitrary base64 string or another vendor's token")
    void doesNotMatchLookalikes() {
        Pattern pattern = PatDetection.pattern();

        assertThat(pattern.matcher("ghp_16C7e42F292c6912E7710c838347Ae178B4a").find()).isFalse();
        assertThat(pattern.matcher("c2VjcmV0LXZhbHVlLXRoYXQtaXMtbm90LWEtdG9rZW4").find()).isFalse();
        assertThat(pattern.matcher("lpat_tooshort").find()).isFalse();
        assertThat(pattern.matcher("not a token at all").find()).isFalse();
    }

    @Test
    @DisplayName("the published prefix is the one the minter uses, so a grep pre-filter works")
    void prefixAgreesWithTheMinter() {
        assertThat(PatDetection.prefix()).isEqualTo(PatTokens.PREFIX + PatTokens.SEPARATOR);
        assertThat(PatTokens.mint().rendered()).startsWith(PatDetection.prefix());
    }
}
