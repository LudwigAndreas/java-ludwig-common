package fixtures.digest;

import java.security.MessageDigest;
import javax.crypto.Mac;

/**
 * The shapes that must not be reported.
 *
 * <p>Four negatives, and the last two are the ones that matter. HMAC-SHA1 is not the digest the rule
 * forbids - HMAC's security does not rest on its hash's collision resistance, it has no practical break,
 * and several external APIs still require it - and a string mentioning a weak name in prose or in an
 * assertion is a test of the rule rather than a violation of it.
 */
public final class StrongDigest {

    private static final String DIGEST_ALGORITHM = "SHA-256";

    /** A legacy marker this code rejects. Naming the algorithm is not selecting it. */
    private static final String REJECTED_MARKER = "legacy-MD5-marker";

    private StrongDigest() {
    }

    public static MessageDigest strongAtTheCallSite() throws Exception {
        return MessageDigest.getInstance("SHA-256");
    }

    public static MessageDigest strongThroughAConstant() throws Exception {
        return MessageDigest.getInstance(DIGEST_ALGORITHM);
    }

    public static Mac interoperableHmac() throws Exception {
        return Mac.getInstance("HmacSHA1");
    }

    public static boolean rejectsLegacyMarker(String marker) {
        return REJECTED_MARKER.equals(marker);
    }
}
