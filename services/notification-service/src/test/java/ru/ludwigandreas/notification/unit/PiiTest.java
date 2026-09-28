package ru.ludwigandreas.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.notification.service.Pii;

/**
 * Masking is the actual control, not a backstop: an address logged as a message argument never
 * reaches the MDC, so the observability module's key-based masking cannot catch it.
 */
class PiiTest {

    @Test
    @DisplayName("an email keeps one character of each part and the top-level domain")
    void masksEmail() {
        assertThat(Pii.address("jonathan@example.com")).isEqualTo("j***@e***.com");
    }

    @Test
    @DisplayName("a short local part or host label is masked entirely rather than half-revealed")
    void masksShortPartsCompletely() {
        assertThat(Pii.address("jo@ab.io")).isEqualTo("***@***.io");
    }

    @Test
    @DisplayName("a webhook URL keeps its host and drops the path and query")
    void masksUrlPathAndQuery() {
        // The host is the partner's identity and is not a secret; the path and the query are where
        // tokens and recipient ids live.
        assertThat(Pii.address("https://partner.example.com/hooks/abc?token=s3cret"))
                .isEqualTo("https://partner.example.com/***");
    }

    @Test
    @DisplayName("an opaque handle keeps one character")
    void masksOpaqueHandle() {
        assertThat(Pii.address("U0123456789")).isEqualTo("U***");
    }

    @Test
    @DisplayName("null and blank are fully masked rather than printed")
    void masksAbsentValues() {
        assertThat(Pii.address(null)).isEqualTo("***");
        assertThat(Pii.address("   ")).isEqualTo("***");
    }

    /**
     * A truncated body is still a body - the first eighty characters of a one-time-code email
     * frequently contain the code - so the only safe summary carries no content at all.
     */
    @Test
    @DisplayName("a rendered body is reduced to its length and never to a prefix")
    void bodyCarriesNoContent() {
        String body = "Hello Jonathan, your code is 481920.";

        assertThat(Pii.body(body)).isEqualTo("<36 chars>");
        assertThat(Pii.body(body)).doesNotContain("481920").doesNotContain("Jonathan");
    }

    /**
     * Case folding uses Locale.ROOT explicitly. In a Turkish locale "I".toLowerCase() is a dotless i,
     * so a default-locale fold would make a suppression written on one node unmatchable on another.
     */
    @Test
    @DisplayName("normalization does not depend on the default locale")
    void normalizationIsLocaleIndependent() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(Pii.normalizeAddress("  INFO@Example.COM ")).isEqualTo("info@example.com");
        } finally {
            Locale.setDefault(previous);
        }
    }
}
