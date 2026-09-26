package ru.ludwigandreas.idempotency.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What a store answered when a key was claimed.
 *
 * @param outcome     whether this caller won, or what the holder is doing
 * @param owner       the request id that holds the key. Equal to the caller's own id when
 *                    {@code outcome} is {@link ClaimOutcome#CLAIMED}, somebody else's otherwise
 * @param fingerprint the fingerprint stored with the claim, or {@code null} when none was stored.
 *                    Compared against the current request's by the caller rather than by the store,
 *                    so that the store stays a store and the decision about what to do with a
 *                    mismatch - refuse, log, both - is made where the request is
 * @param response    the response to replay, present only for a {@link ClaimOutcome#COMPLETED}
 *                    standalone claim that stored one
 * @param expiresAt   when the claim stops being recognised, which is what a {@code Retry-After} on an
 *                    in-flight duplicate is derived from
 */
public record ClaimResult(
        ClaimOutcome outcome,
        UUID owner,
        String fingerprint,
        StoredResponse response,
        Instant expiresAt) {

    /** Rejects a result that names no outcome or no owner. */
    public ClaimResult {
        if (outcome == null) {
            throw new IllegalArgumentException("A ClaimResult needs an outcome");
        }
        if (owner == null) {
            throw new IllegalArgumentException("A ClaimResult needs the owner of the key");
        }
    }

    /** A won claim. */
    public static ClaimResult claimed(UUID owner, String fingerprint, Instant expiresAt) {
        return new ClaimResult(ClaimOutcome.CLAIMED, owner, fingerprint, null, expiresAt);
    }

    /** Whether this caller won the claim and must do the work. */
    public boolean won() {
        return outcome.won();
    }

    /** The response to replay, if the completed holder stored one. */
    public Optional<StoredResponse> replayable() {
        return Optional.ofNullable(response);
    }

    /**
     * Whether the claim stored a fingerprint that is not {@code candidate}.
     *
     * <p>A claim with no stored fingerprint never mismatches. That is deliberate rather than
     * conservative: a claim written by a caller that did not fingerprint, or by a release before
     * fingerprinting was switched on, must not start refusing the retries it was taken to protect.
     * The counter for this outcome is the one worth alerting on, because a mismatch means a client is
     * recycling keys and nobody will report it - see {@code IdempotencyMetrics}.
     *
     * @param candidate the current request's fingerprint, or {@code null} if it was not computed
     * @return whether the two disagree
     */
    public boolean fingerprintMismatch(String candidate) {
        return fingerprint != null && candidate != null && !fingerprint.equals(candidate);
    }
}
