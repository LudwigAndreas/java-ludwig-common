package ru.ludwigandreas.notification.service.channel;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs and verifies the webhook payloads this service sends and the receipts it accepts.
 *
 * <p>The signed value is {@code <timestamp>.<body>}, not the body alone. A signature over the body
 * by itself is replayable forever: anybody who observes one valid request can repeat it whenever
 * they like, and against the receipt endpoint that means permanently suppressing a recipient's
 * address at a time of their choosing. Binding a timestamp into the signature and rejecting old ones
 * bounds that window to the tolerance.
 *
 * <p>Comparison is constant-time. A byte-by-byte {@code equals} that returns early leaks, through
 * timing, how many leading bytes of a guess were right - which turns forging a signature from
 * infeasible into a few thousand requests.
 */
public final class HmacSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private HmacSigner() {
    }

    /** Hex-encoded HMAC-SHA256 over {@code <timestamp>.<payload>}. */
    public static String sign(String secret, long timestampEpochSeconds, String payload) {
        return hex(mac(secret, timestampEpochSeconds + "." + payload));
    }

    /** Constant-time comparison of a presented signature against the expected one. */
    public static boolean verify(String secret, long timestampEpochSeconds, String payload,
                                 String presentedSignature) {
        if (presentedSignature == null || presentedSignature.isBlank()) {
            return false;
        }
        byte[] expected = mac(secret, timestampEpochSeconds + "." + payload);
        byte[] presented;
        try {
            presented = HexFormat.of().parseHex(presentedSignature.trim().toLowerCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // A signature that is not hex at all cannot match; failing here rather than throwing
            // keeps a malformed header a plain rejection instead of a 500.
            return false;
        }
        return MessageDigest.isEqual(expected, presented);
    }

    private static byte[] mac(String secret, String value) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("A signing secret is required; refusing to sign with an empty key");
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Could not compute " + ALGORITHM + " signature", e);
        }
    }

    private static String hex(byte[] value) {
        return HexFormat.of().formatHex(value);
    }
}
