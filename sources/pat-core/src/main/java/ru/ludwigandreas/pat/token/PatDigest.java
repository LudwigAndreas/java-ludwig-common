package ru.ludwigandreas.pat.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Turns a secret into the digest that is stored in its place, and compares digests without leaking timing.
 *
 * <h2>Why a plain SHA-256 and not bcrypt, scrypt or Argon2</h2>
 *
 * <p>This is the decision in this module most likely to be "corrected" by a reviewer applying a rule that is
 * right almost everywhere else, so the reasoning is recorded here rather than in a design document nobody
 * reads at the point of change.
 *
 * <p>A password key-derivation function exists to make brute-forcing a <b>low-entropy, human-chosen</b>
 * secret expensive. Its entire value is the work factor, and the work factor is worth paying because the
 * search space is small enough to enumerate - people choose from a distribution a few million entries wide.
 *
 * <p>A personal-access-token secret is {@value PatTokens#SECRET_LENGTH} base62 characters from {@link
 * java.security.SecureRandom}. There is no distribution to exploit and no search space to enumerate: the
 * work factor would be protecting against an attack that cannot be mounted. What it would reliably add is
 * tens to hundreds of milliseconds of CPU on <b>every token exchange</b> - which is the one endpoint in the
 * platform that is simultaneously on the hot path and the system's only brute-force target. A KDF there buys
 * no security and donates a denial-of-service surface.
 *
 * <p><b>This reasoning depends entirely on the generator, and no mechanical check can assert that.</b> It
 * holds because {@link PatTokens#mint()} uses {@code SecureRandom} at full width. If that ever changes - to a
 * shorter secret, to a human-chosen suffix, to a predictable prefix for readability, to anything with
 * structure - then the premise is gone and this becomes an unsalted hash of a guessable value, which is the
 * textbook mistake. There is no rule in {@code architecture-rules} or {@code checkstyle-rules} that can catch
 * that, because it is a statement about entropy rather than about structure, which is why it is written here
 * at the point of the rule, as this repository's convention requires. Whoever changes the generator owes this
 * comment a second look.
 *
 * <p>What Checkstyle <em>can</em> check is that the algorithm named is not broken, and does:
 * {@code WeakDigestAlgorithm} fails the build on MD5, SHA-1 and friends. The algorithm is a string literal
 * argument and ArchUnit reads bytecode, where a constant's value is not exposed - the same division of labour
 * that puts {@code SecondRedactionMask} in Checkstyle.
 *
 * <h2>No salt, and that is also deliberate</h2>
 *
 * <p>A salt defeats precomputation - rainbow tables - by making each stored hash depend on a per-record
 * value. Precomputation requires a search space worth precomputing, which is the same premise the KDF
 * argument turns on and is equally absent here: nobody builds a rainbow table over 256 bits of uniform
 * randomness. Omitting the salt is what makes the digest a usable lookup key, which is what lets verification
 * be an indexed point read rather than a scan.
 */
public final class PatDigest {

    /**
     * The digest algorithm.
     *
     * <p>A constant rather than a literal at the call site so there is exactly one place to change it, and so
     * that the Checkstyle rule has a declaration to match as well as a call.
     */
    private static final String ALGORITHM = "SHA-256";

    private PatDigest() {
    }

    /** The lowercase hex digest of a secret, which is what a stored record holds in the secret's place. */
    public static String of(PatSecret secret) {
        if (secret == null) {
            throw new IllegalArgumentException("secret must not be null");
        }
        return hex(secret.reveal());
    }

    /** The digest of a raw string, for the parse path where no carrier has been built yet. */
    public static String ofRaw(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("secret must not be blank");
        }
        return hex(secret);
    }

    /**
     * Whether two digests match, in time independent of where they first differ.
     *
     * <p>{@link MessageDigest#isEqual} rather than {@link String#equals}, and this matters more than it
     * looks. {@code String.equals} returns at the first differing character, so the time it takes reveals the
     * length of the matching prefix. Against a remote attacker that signal is usually buried in network
     * jitter - but "usually" is doing load-bearing work in that sentence, the attacker chooses how many
     * samples to average, and the cost of not having the problem is one method call.
     */
    public static boolean matches(String presentedDigest, String storedDigest) {
        return constantTimeEquals(presentedDigest, storedDigest);
    }

    /**
     * Constant-time string comparison, used for digests and for secrets.
     *
     * <p>Null-safe and deliberately returns false for a null on either side rather than throwing: this sits
     * on the verification path, where a null stored digest is the ordinary representation of "this token has
     * been revoked and its digest cleared", and an exception there would turn a routine refusal into a 500.
     *
     * <p>The length check before the comparison does leak length, which is accepted: both sides here are
     * fixed-width hex digests or fixed-width base62 secrets, so the length carries no information an
     * attacker does not already have from the format itself.
     */
    static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException cause) {
            // Every JVM this platform runs on is required by the Java specification to provide SHA-256, so
            // this is unreachable rather than merely unlikely. Wrapped rather than swallowed because a
            // silently absent digest algorithm would mean every token verifies against nothing.
            throw new IllegalStateException(ALGORITHM + " is required by the Java platform specification"
                    + " and is unavailable on this JVM", cause);
        }
    }
}
