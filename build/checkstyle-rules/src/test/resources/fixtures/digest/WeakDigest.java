package fixtures.digest;

import java.security.MessageDigest;

/** Selects a broken digest algorithm two ways: at the call site, and through a constant. */
public final class WeakDigest {

    private static final String HASH_ALGORITHM = "SHA-1";

    private WeakDigest() {
    }

    public static MessageDigest atTheCallSite() throws Exception {
        return MessageDigest.getInstance("MD5");
    }

    public static MessageDigest throughAConstant() throws Exception {
        return MessageDigest.getInstance(HASH_ALGORITHM);
    }
}
