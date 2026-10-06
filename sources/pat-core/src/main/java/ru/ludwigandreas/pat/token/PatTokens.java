package ru.ludwigandreas.pat.token;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.zip.CRC32C;

/**
 * Mints and parses the wire form of a personal access token: {@code lpat_<keyId>_<secret>_<checksum>}.
 *
 * <p>Every one of the four parts earns its place, and three of them are there for operational reasons rather
 * than cryptographic ones.
 *
 * <table border="1">
 *   <caption>What each part is for</caption>
 *   <tr><th>Part</th><th>Why</th></tr>
 *   <tr>
 *     <td>{@code lpat_}</td>
 *     <td>Makes the credential <b>findable</b>. A secret scanner, {@code gitleaks} or a pre-receive hook can
 *         only match a known shape, so a format nobody can grep for is one whose leaks are discovered during
 *         the incident rather than before it. The pattern is published as a resource of this module for
 *         exactly that purpose.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code keyId}</td>
 *     <td>Makes verification an <b>indexed point read</b>. Without it, verification is "load every candidate
 *         row and compare digests", which is how systems end up accidentally running a hash per row. It is
 *         not secret and carries no entitlement - it is a database key that happens to travel with the
 *         secret.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code secret}</td>
 *     <td>{@value #SECRET_LENGTH} base62 characters from {@link SecureRandom}. Never stored; only
 *         its digest is. The width is what makes {@link PatDigest}'s choice of a plain digest correct, and
 *         that dependency is stated there.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code checksum}</td>
 *     <td>Rejects a mistyped or truncated token with <b>no database access</b>, which matters under a flood
 *         of garbage, and lets a scanner avoid false positives on a random base64 string.</td>
 *   </tr>
 * </table>
 *
 * <p><b>The checksum is not a security control, and this sentence exists because "it has a checksum" is read
 * as "it is tamper-proof" by the next person.</b> CRC32C is not a MAC: anyone can compute it, so anyone
 * can produce a well-formed token for any key id they like. It stops a typo and a truncation, nothing more.
 * What stops a forgery is that the secret's digest does not match, and that check reaches the database. There
 * is nothing to detect here and therefore nothing a build could check - only a misreading to pre-empt, which
 * is why this is a comment rather than a rule, and why it is recorded in the change's enforcement table among
 * the conventions no build can hold.
 */
public final class PatTokens {

    /** The fixed, scannable prefix. Changing it invalidates every issued token and every scanner rule. */
    public static final String PREFIX = "lpat";

    /** The separator between parts. Not present in base64url or in hex, so the parse is unambiguous. */
    public static final char SEPARATOR = '_';

    /**
     * The alphabet the random parts are drawn from: base62, and deliberately <b>not</b> base64url.
     *
     * <p>This was base64url - {@code Base64.getUrlEncoder().withoutPadding()} - and that was a bug, found by
     * {@code PatTokenFormatTest.rendersTheDocumentedShape} rather than by reading the code. The base64url
     * alphabet is {@code A-Za-z0-9-_}, which <b>contains the separator</b>. So roughly one token in twenty had
     * an underscore inside its key id or secret, {@code split("_")} returned five parts instead of four, and
     * that token was unparseable and therefore permanently unusable - while nineteen in twenty worked
     * perfectly.
     *
     * <p>Worth dwelling on, because the shape of this defect is the reason the test draws many samples rather
     * than one: a single round-trip assertion passes about 95% of the time. It would have passed in
     * development, passed in CI, and produced a support ticket about tokens that "sometimes don't work" with
     * no reproducible case.
     *
     * <p>Fixed by narrowing the alphabet rather than by changing the separator. {@code _} after a short prefix
     * is the industry-wide convention for a scannable credential - every scanner's heuristics and every
     * reviewer's eye expect it - so the separator is the part worth keeping. Base62 is uniform here because
     * each character is an independent {@link SecureRandom#nextInt(int)} draw, which is unbiased; encoding
     * bytes and substituting the offending characters afterwards would have been the tempting one-line fix and
     * would have made the distribution non-uniform, quietly reducing entropy.
     */
    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    /**
     * Secret width in characters.
     *
     * <p>43 base62 characters is log2(62^43) is about 256 bits. {@link PatDigest}'s decision to use a plain digest
     * rather than a password KDF rests on this width and on the generator being {@code SecureRandom}; see that
     * class before reducing it.
     */
    public static final int SECRET_LENGTH = 43;

    /**
     * Key-id width in characters.
     *
     * <p>11 base62 characters is about 65 bits, so collisions are not a practical concern even across many
     * millions of tokens - and the key id has a unique index behind it, so a collision is a refused insert
     * rather than a mix-up.
     */
    private static final int KEY_ID_LENGTH = 11;

    private static final int PART_COUNT = 4;

    /**
     * Positions within the separated wire form.
     *
     * <p>Named rather than written as literals at the call site. Checkstyle's {@code MagicNumber} forced the
     * last of them, and the result reads better than what it replaced: {@code parts[CHECKSUM_PART]} says what
     * is being read where {@code parts[3]} required counting the parts in the format string first.
     */
    private static final int PREFIX_PART = 0;

    private static final int KEY_ID_PART = 1;

    private static final int SECRET_PART = 2;

    private static final int CHECKSUM_PART = 3;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PatTokens() {
    }

    /** A freshly minted token: a new key id, a new secret, and the rendered wire form. */
    public static MintedToken mint() {
        String keyId = random(KEY_ID_LENGTH);
        PatSecret secret = PatSecret.of(random(SECRET_LENGTH));
        return new MintedToken(keyId, secret, render(keyId, secret));
    }

    /**
     * Renders the wire form. Package-private deliberately: a caller outside this package that renders a token
     * itself is a caller holding a raw secret, which {@code credentials.secret-carrier-stays-inside} forbids.
     */
    static String render(String keyId, PatSecret secret) {
        String body = PREFIX + SEPARATOR + keyId + SEPARATOR + secret.reveal();
        return body + SEPARATOR + checksum(body);
    }

    /**
     * Parses a presented value, or returns empty when it is not a well-formed token.
     *
     * <p>Returns {@link Optional} rather than throwing, because a malformed credential is an expected input on
     * a public endpoint rather than an exceptional one - an exception per garbage request is a stack trace per
     * garbage request. The caller renders the same uniform failure for this as for a wrong secret, so nothing
     * downstream needs to distinguish them, and deliberately so: a distinguishable "malformed" response tells
     * an attacker which half of their guess was wrong.
     */
    public static Optional<ParsedToken> parse(String presented) {
        if (presented == null || presented.isBlank()) {
            return Optional.empty();
        }
        String[] parts = presented.split(String.valueOf(SEPARATOR));
        if (parts.length != PART_COUNT || !PREFIX.equals(parts[PREFIX_PART])) {
            return Optional.empty();
        }
        String keyId = parts[KEY_ID_PART];
        String secret = parts[SECRET_PART];
        String presentedChecksum = parts[CHECKSUM_PART];
        if (keyId.isEmpty() || secret.isEmpty()) {
            return Optional.empty();
        }
        String body = PREFIX + SEPARATOR + keyId + SEPARATOR + secret;
        // Constant-time, even though the checksum is not a secret and this is not a security boundary.
        // The cost is nil and it keeps one habit rather than two: a reader who sees a fast comparison here
        // and a slow one in PatDigest has to work out which one was deliberate.
        if (!PatDigest.constantTimeEquals(presentedChecksum, checksum(body))) {
            return Optional.empty();
        }
        return Optional.of(new ParsedToken(keyId, PatDigest.ofRaw(secret)));
    }

    private static String checksum(String body) {
        CRC32C crc = new CRC32C();
        crc.update(body.getBytes(StandardCharsets.UTF_8));
        return Long.toHexString(crc.getValue());
    }

    /**
     * A uniformly random base62 string of the given length.
     *
     * <p>One independent {@link SecureRandom#nextInt(int)} draw per character. {@code nextInt(bound)} is
     * documented to be unbiased, which is the property that matters: the alternative of taking a random byte
     * and reducing it modulo 62 would favour the first eight characters of the alphabet, costing a fraction of
     * a bit per character for no reason.
     */
    private static String random(int length) {
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return value.toString();
    }

    /**
     * A newly minted token, in the three forms its caller needs at once.
     *
     * @param keyId    the indexed lookup key, stored
     * @param secret   the raw secret, stored only as {@link PatDigest#of}
     * @param rendered the full wire form, returned to the caller exactly once and never again
     */
    public record MintedToken(String keyId, PatSecret secret, String rendered) {

        /** The digest to persist in the secret's place. */
        public String digest() {
            return PatDigest.of(secret);
        }

        /**
         * Masked, so that a minted token logged or interpolated by mistake prints nothing useful.
         *
         * <p>Hand-written rather than generated, which is the point: a record's generated {@code toString()}
         * prints every component, and two of the three here are the secret. A record is still the right shape
         * for this - three related values, no behaviour - but its default rendering is exactly wrong, and
         * that is easy to miss because the default is normally what you want.
         */
        @Override
        public String toString() {
            return "MintedToken[keyId=" + keyId + ", secret=not shown, rendered=not shown]";
        }
    }

    /**
     * What a presented token yields once parsed: a lookup key and a digest to compare.
     *
     * <p>Carries no secret, which is why it is safe for this one to leave the package and to have a generated
     * {@code toString()}. The digest is derived and not reversible, and it is already stored in the clear in
     * the token table.
     */
    public record ParsedToken(String keyId, String digest) {
    }
}
