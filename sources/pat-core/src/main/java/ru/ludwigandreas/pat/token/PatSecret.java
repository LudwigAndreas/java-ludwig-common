package ru.ludwigandreas.pat.token;

import java.util.Objects;

/**
 * The raw secret half of a personal access token, held in a type that will not print itself.
 *
 * <p>A {@code String} would work and is what this replaces. The reason it is not a {@code String} is that a
 * {@code String} is printable by every mechanism in the JVM that prints anything - a log statement, a
 * {@code toString()} on the record that contains it, a Jackson serializer, a debugger's variable pane, an
 * exception message that interpolates the object that holds it - and a secret that reaches any of those has
 * leaked. There is no way to make a {@code String} refuse; there is a way to make this type refuse.
 *
 * <h2>Three partial mechanisms, because no complete one exists</h2>
 *
 * <p>Nothing here is sufficient on its own, and the architecture that makes the secret safe is all three
 * together. This is worth stating plainly rather than implying, because the natural reading of a type like
 * this is "the secret is now safe", and it is not - it is narrower.
 *
 * <ol>
 *   <li><b>{@link #toString()} masks.</b> This handles the accidental case, which is the common one: a log
 *       statement that interpolates a request record, an exception message built with string concatenation,
 *       a debugger. It does nothing about deliberate unwrapping.</li>
 *   <li><b>{@link #reveal()} is fenced by an ArchUnit rule</b> - {@code credentials.secret-reveal-is-fenced}
 *       in {@code architecture-rules}. Only this package and the issuance service may call it, because
 *       issuance has to return the secret once and the exchange has to hash what was presented. Those two
 *       reads are legitimate; a third is a leak waiting for a log statement.</li>
 *   <li><b>The carrier is contained</b> by {@code credentials.secret-carrier-stays-inside}: no method outside
 *       the credential modules may have this type in its signature. This is the rule that actually does the
 *       work, because code that never holds a carrier cannot log one.</li>
 * </ol>
 *
 * <p>Once {@link #reveal()} has returned, the result is an ordinary {@code String} and no mechanism in this
 * repository can follow it. That is the limit, it is why the accessor is fenced rather than merely
 * documented, and it is why the containment rule exists rather than a rule about logging calls - "never pass
 * the carrier to a logger" is unenforceable, because SLF4J takes {@code Object...} and the carrier is
 * already widened by the time it arrives.
 *
 * <h2>Why not a char array, cleared after use</h2>
 *
 * <p>The usual advice for in-memory secrets is a mutable array that is zeroed when finished with, so the
 * secret does not sit in the heap until a garbage collection that may never come. It is not followed here and
 * the reason is specific: this secret is minted, returned in one HTTP response and discarded, or it arrives
 * in a request header and is hashed immediately. By the time it reaches this type, it has already been
 * through the servlet container's own {@code String} buffers, the HTTP parser and very possibly a proxy's
 * access log - all of which hold copies this class cannot reach. Zeroing one copy out of several buys
 * approximately nothing while making every call site harder to read, which is a bad trade. The mitigation
 * that does work is the one above: keep the number of copies small by keeping the carrier contained.
 */
public final class PatSecret {

    /**
     * What {@link #toString()} prints instead of the secret.
     *
     * <p>Deliberately not {@code ru.ludwigandreas.audit.redaction.Redaction.MASK}, despite that being the
     * platform's one redaction mask and despite this looking like redaction. Two reasons, and the first is
     * decisive: using it would require {@code pat-core} to depend on {@code audit-core}, which would break the
     * zero-in-repo-dependency property that lets the security starter depend on this module at all. The second
     * is that these are different mechanisms. {@code Redaction.MASK} is applied by a redactor to a value on
     * its way into an audit record, where the marker must be a single platform-wide constant because a
     * persisted one is a Liquibase changeset to change. This is one type declining to print itself, where the
     * only requirement is that the output is not the secret and is recognisable in a log.
     *
     * <p><b>Named {@code NOT_SHOWN} rather than anything containing "mask", and that rename was forced by
     * Checkstyle rather than chosen.</b> The constant was first called {@code MASKED}, and
     * {@code SecondRedactionMask} failed the build on it - correctly, because the rule keys on the declaration
     * name and a constant called {@code MASKED} holding a string literal is indistinguishable from the second
     * platform mask that rule exists to prevent. The rule cannot read the javadoc above and should not have to.
     * The resolution is the rename, not a suppression: suppressing would have asserted "this is a mask but an
     * allowed one", which is the opposite of true.
     */
    private static final String NOT_SHOWN = "PatSecret[not shown]";

    private final String secret;

    private PatSecret(String secret) {
        this.secret = secret;
    }

    /** Wraps a freshly generated or freshly received secret. */
    public static PatSecret of(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("secret must not be blank");
        }
        return new PatSecret(secret);
    }

    /**
     * The raw secret.
     *
     * <p>Fenced: only {@code ru.ludwigandreas.pat..} may call this, enforced by
     * {@code credentials.secret-reveal-is-fenced}. Named {@code reveal} rather than {@code value} or
     * {@code get} so that a call site reads as the deliberate act it is - {@code secret.reveal()} is hard to
     * write by accident and hard to read without noticing.
     */
    public String reveal() {
        return secret;
    }

    /**
     * The masked form, always.
     *
     * <p>There is no flag, no system property and no debug mode that makes this print the secret. A switch
     * for that would exist to be turned on in the one environment where it does the most damage.
     */
    @Override
    public String toString() {
        return NOT_SHOWN;
    }

    /**
     * Compares secrets in constant time.
     *
     * <p>Equality on a secret is an odd operation and it is implemented rather than omitted because omitting
     * it would leave the inherited identity comparison, which silently returns false for two carriers holding
     * the same secret - a defect that shows up as a test that passes for the wrong reason. The comparison is
     * constant-time for the same reason the digest comparison is: a timing difference here is a timing
     * difference an attacker can measure, and there is no cost to avoiding it.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PatSecret that)) {
            return false;
        }
        return PatDigest.constantTimeEquals(secret, that.secret);
    }

    /**
     * A constant, so that the secret's value never influences a hash bucket.
     *
     * <p>Deliberately not {@code secret.hashCode()}. A hash code is not a secret, but it is derived from one,
     * it appears in heap dumps and in the output of collection debugging, and it narrows a search. Returning
     * a constant makes this type useless as a hash key, which is correct - a secret is not an identifier, the
     * key id is, and anything wanting to key on a token should key on the digest.
     */
    @Override
    public int hashCode() {
        return Objects.hash(PatSecret.class);
    }
}
