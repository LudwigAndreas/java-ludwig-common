package ru.ludwigandreas.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.notification.service.channel.HmacSigner;

/**
 * The webhook channel's authentication, and the receipt endpoint's. A forged bounce suppresses a real
 * recipient's address, which makes a loose verifier a denial-of-service against individual people.
 */
class HmacSignerTest {

    private static final String SECRET = "a-shared-secret";
    private static final String PAYLOAD = "{\"deliveryId\":\"7f\"}";
    private static final long TIMESTAMP = 1_772_000_000L;

    @Test
    @DisplayName("a signature verifies against the same secret, timestamp and body")
    void roundTrips() {
        String signature = HmacSigner.sign(SECRET, TIMESTAMP, PAYLOAD);

        assertThat(HmacSigner.verify(SECRET, TIMESTAMP, PAYLOAD, signature)).isTrue();
    }

    /**
     * The timestamp is inside the signed value rather than beside it. A signature over the body alone
     * is replayable forever: a bounce captured once could be replayed months later to suppress that
     * address at a moment of the attacker's choosing.
     */
    @Test
    @DisplayName("a signature does not verify against a different timestamp")
    void timestampIsCovered() {
        String signature = HmacSigner.sign(SECRET, TIMESTAMP, PAYLOAD);

        assertThat(HmacSigner.verify(SECRET, TIMESTAMP + 1, PAYLOAD, signature)).isFalse();
    }

    @Test
    @DisplayName("a modified body does not verify")
    void bodyIsCovered() {
        String signature = HmacSigner.sign(SECRET, TIMESTAMP, PAYLOAD);

        assertThat(HmacSigner.verify(SECRET, TIMESTAMP, PAYLOAD + " ", signature)).isFalse();
    }

    @Test
    @DisplayName("a signature from a different secret does not verify")
    void secretIsCovered() {
        String signature = HmacSigner.sign("another-secret", TIMESTAMP, PAYLOAD);

        assertThat(HmacSigner.verify(SECRET, TIMESTAMP, PAYLOAD, signature)).isFalse();
    }

    @Test
    @DisplayName("a malformed signature is a rejection rather than a 500")
    void malformedSignatureIsRejected() {
        assertThat(HmacSigner.verify(SECRET, TIMESTAMP, PAYLOAD, "not-hex-at-all")).isFalse();
        assertThat(HmacSigner.verify(SECRET, TIMESTAMP, PAYLOAD, null)).isFalse();
        assertThat(HmacSigner.verify(SECRET, TIMESTAMP, PAYLOAD, "  ")).isFalse();
    }

    @Test
    @DisplayName("case does not matter, because hex encoding is not case-sensitive")
    void signatureCaseIsIgnored() {
        String signature = HmacSigner.sign(SECRET, TIMESTAMP, PAYLOAD);

        assertThat(HmacSigner.verify(SECRET, TIMESTAMP, PAYLOAD, signature.toUpperCase(java.util.Locale.ROOT)))
                .isTrue();
    }

    /**
     * An empty key would produce signatures every receiver accepts from anyone, so refusing is the
     * only defensible behaviour - and the startup validator makes sure it never gets this far.
     */
    @Test
    @DisplayName("signing with no secret is refused rather than defaulted")
    void refusesToSignWithoutASecret() {
        assertThatThrownBy(() -> HmacSigner.sign("", TIMESTAMP, PAYLOAD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("signing secret");
    }
}
