package ru.ludwigandreas.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ru.ludwigandreas.notification.service.model.RecipientRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two labels a recipient produces, and why they cannot be the same one.
 *
 * <p>These exist because the obvious single implementation - one readable label used everywhere -
 * leaks. The readable form is written into the status trail (kept six months), into log lines and
 * into problem details, all of which outlive the seven days the address itself is kept; the dedup key
 * is written into a column that survives ninety days. One label cannot be both masked and unique.
 */
class RecipientRefTest {

    @Test
    @DisplayName("a user recipient keeps its subject in both forms")
    void userSubjectIsNotSecret() {
        RecipientRef ref = RecipientRef.ofUser("8f2c-user");

        assertThat(ref.reference()).isEqualTo("user:8f2c-user");
        assertThat(ref.dedupToken()).isEqualTo("user:8f2c-user");
    }

    /**
     * Written into the six-month status trail and into logs, so it must not carry the address.
     */
    @Test
    @DisplayName("the readable reference masks an address")
    void readableReferenceIsMasked() {
        RecipientRef ref = RecipientRef.ofAddress("jonathan@example.com");

        assertThat(ref.reference()).isEqualTo("address:j***@e***.com");
        assertThat(ref.reference()).doesNotContain("jonathan").doesNotContain("example.com");
    }

    /**
     * The masked form cannot be used as a dedup key: masking is lossy, so two different addresses
     * collapse to the same label and the unique index would reject the second recipient's delivery as
     * a duplicate of the first - silently dropping a notification.
     */
    @Test
    @DisplayName("two addresses that mask identically still get different dedup tokens")
    void dedupTokenIsUniqueWhereTheMaskIsNot() {
        RecipientRef first = RecipientRef.ofAddress("jonathan@example.com");
        RecipientRef second = RecipientRef.ofAddress("jane@example.com");

        assertThat(first.reference()).isEqualTo(second.reference());
        assertThat(first.dedupToken()).isNotEqualTo(second.dedupToken());
    }

    /**
     * And the dedup key cannot be the raw address: it lives on the delivery for the full ninety-day
     * retention, long past the seven days the address is kept, so embedding it would defeat the scrub.
     */
    @Test
    @DisplayName("the dedup token carries no address")
    void dedupTokenCarriesNoAddress() {
        RecipientRef ref = RecipientRef.ofAddress("jonathan@example.com");

        assertThat(ref.dedupToken())
                .doesNotContain("jonathan")
                .doesNotContain("example.com")
                .startsWith("address:")
                // Hex SHA-256, so it is stable, collision-free in practice, and one-way.
                .matches("address:[0-9a-f]{64}");
    }

    @Test
    @DisplayName("the dedup token is stable and case-insensitive, like the suppression list")
    void dedupTokenIsNormalized() {
        assertThat(RecipientRef.ofAddress("  Jonathan@Example.COM ").dedupToken())
                .isEqualTo(RecipientRef.ofAddress("jonathan@example.com").dedupToken());
    }

    @Test
    @DisplayName("a recipient must name exactly one identifier")
    void identifierIsRequired() {
        assertThatThrownBy(() -> new RecipientRef(
                ru.ludwigandreas.notification.service.model.RecipientKind.USER,
                null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecipientRef(
                ru.ludwigandreas.notification.service.model.RecipientKind.ADDRESS,
                null, "  ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
