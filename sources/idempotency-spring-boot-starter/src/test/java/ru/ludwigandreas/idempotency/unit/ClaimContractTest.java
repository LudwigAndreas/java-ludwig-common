package ru.ludwigandreas.idempotency.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.idempotency.api.ClaimMode;
import ru.ludwigandreas.idempotency.api.ClaimOutcome;
import ru.ludwigandreas.idempotency.api.ClaimRequest;
import ru.ludwigandreas.idempotency.api.ClaimResult;
import ru.ludwigandreas.idempotency.api.IdempotencyScopes;
import ru.ludwigandreas.idempotency.api.StoredResponse;

/**
 * The invariants the published value types enforce, each of which exists because breaking it is silent.
 */
class ClaimContractTest {

    @Test
    @DisplayName("a claim must name its mode; there is no default")
    void modeIsRequired() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ClaimRequest(
                        null, "http", "k", UUID.randomUUID(), null, Duration.ofHours(1), null))
                .withMessageContaining("no default");
    }

    @Test
    @DisplayName("a standalone claim must carry a lease, so a dead holder's key is reclaimable")
    void standaloneNeedsALease() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ClaimRequest.standalone("http", "k", UUID.randomUUID(),
                        Duration.ofHours(1), null))
                .withMessageContaining("reclaimable");
    }

    @Test
    @DisplayName("a transactional claim needs no lease, because it has no in-progress state to lease")
    void transactionalNeedsNoLease() {
        ClaimRequest request = ClaimRequest.transactional("kafka", "k", UUID.randomUUID(),
                Duration.ofHours(1));

        assertThat(request.mode()).isEqualTo(ClaimMode.TRANSACTIONAL);
        assertThat(request.lease()).isNull();
    }

    @Test
    @DisplayName("a zero or negative ttl is refused, because a claim with no window dedups nothing")
    void ttlMustBeAWindow() {
        assertThatIllegalArgumentException().isThrownBy(() -> ClaimRequest.transactional(
                "http", "k", UUID.randomUUID(), Duration.ZERO));
    }

    @Test
    @DisplayName("a claim with no stored fingerprint never mismatches")
    void noFingerprintNeverMismatches() {
        ClaimResult claim = new ClaimResult(ClaimOutcome.COMPLETED, UUID.randomUUID(), null, null,
                Instant.now());

        // Deliberate rather than conservative: a claim written by a caller that did not fingerprint, or by
        // a release before fingerprinting was switched on, must not start refusing the retries it was taken
        // to protect.
        assertThat(claim.fingerprintMismatch("abc")).isFalse();
    }

    @Test
    @DisplayName("a claim mismatches only when both fingerprints are present and differ")
    void mismatchNeedsBothSides() {
        ClaimResult claim = new ClaimResult(ClaimOutcome.COMPLETED, UUID.randomUUID(), "abc", null,
                Instant.now());

        assertThat(claim.fingerprintMismatch("abc")).isFalse();
        assertThat(claim.fingerprintMismatch(null)).isFalse();
        assertThat(claim.fingerprintMismatch("def")).isTrue();
    }

    @Test
    @DisplayName("only CLAIMED means the caller must do the work")
    void onlyClaimedWins() {
        assertThat(ClaimOutcome.CLAIMED.won()).isTrue();
        assertThat(ClaimOutcome.IN_PROGRESS.won()).isFalse();
        assertThat(ClaimOutcome.COMPLETED.won()).isFalse();
    }

    @Test
    @DisplayName("a stored response copies its body in and out")
    void storedResponseIsImmutable() {
        byte[] original = {1, 2, 3};
        StoredResponse response = StoredResponse.of(201, "application/json", original);

        original[0] = 9;
        assertThat(response.body()).containsExactly(1, 2, 3);

        byte[] read = response.body();
        read[0] = 9;
        assertThat(response.body()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("a stored response refuses a number that is not an HTTP status")
    void storedResponseValidatesStatus() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StoredResponse(42, null, Map.of(), new byte[0]));
    }

    @Test
    @DisplayName("an endpoint scope is per method and per mapped path")
    void endpointScopesAreDistinct() {
        assertThat(IdempotencyScopes.forEndpoint("POST", "/api/v1/orders"))
                .isEqualTo("http:post:/api/v1/orders")
                .isNotEqualTo(IdempotencyScopes.forEndpoint("PATCH", "/api/v1/orders"))
                .isNotEqualTo(IdempotencyScopes.forEndpoint("POST", "/api/v1/invoices"));
    }

    @Test
    @DisplayName("a topic scope keeps consumer keys out of the HTTP namespace")
    void topicScopeIsNamespaced() {
        assertThat(IdempotencyScopes.forTopic("platform.orders"))
                .startsWith(IdempotencyScopes.KAFKA)
                .isNotEqualTo(IdempotencyScopes.HTTP);
    }
}
