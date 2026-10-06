package ru.ludwigandreas.pat.exchange;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Set;

/**
 * Whether a source address falls inside a token's CIDR allowlist.
 *
 * <p>Hand-rolled rather than pulled from a library, because the only dependency that would save this code is
 * one this module does not otherwise need, and the arithmetic is twenty lines. Supports IPv4 and IPv6 by
 * comparing the leading {@code prefix} bits of the raw address, which is all CIDR containment is.
 *
 * <p><b>Fails closed.</b> An unparseable CIDR, an unparseable source address or a null source all deny. The
 * alternative - treating "I could not tell" as "allowed" - would mean a typo in an allowlist silently turns
 * the restriction off, which is the worst available outcome for a control whose entire purpose is to narrow
 * where a credential works.
 */
final class CidrAllowlist {

    private static final int BITS_PER_BYTE = 8;

    /** Low eight bits, for widening a signed byte and for masking a partial prefix byte. */
    private static final int BYTE_MASK = 0xFF;

    private CidrAllowlist() {
    }

    /**
     * Whether the source is permitted.
     *
     * <p>An <b>empty</b> allowlist permits everything, which is not the same as failing closed: it is the
     * absence of a restriction rather than a restriction nobody could evaluate. Most tokens have no
     * allowlist, and requiring one would make the common case the configured case.
     */
    static boolean permits(Set<String> allowlist, String source) {
        if (allowlist == null || allowlist.isEmpty()) {
            return true;
        }
        if (source == null || source.isBlank()) {
            return false;
        }
        byte[] address;
        try {
            address = InetAddress.getByName(source).getAddress();
        } catch (UnknownHostException cause) {
            return false;
        }
        for (String cidr : allowlist) {
            if (contains(cidr, address)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String cidr, byte[] address) {
        int slash = cidr.indexOf('/');
        if (slash < 0) {
            return false;
        }
        byte[] network;
        int prefix;
        try {
            network = InetAddress.getByName(cidr.substring(0, slash)).getAddress();
            prefix = Integer.parseInt(cidr.substring(slash + 1).trim());
        } catch (UnknownHostException | NumberFormatException cause) {
            return false;
        }
        // A mixed-family comparison is not a match rather than an error: an IPv4 allowlist entry and an
        // IPv6 source are both legitimate, they simply do not contain one another.
        if (network.length != address.length || prefix < 0 || prefix > network.length * BITS_PER_BYTE) {
            return false;
        }
        int fullBytes = prefix / BITS_PER_BYTE;
        for (int i = 0; i < fullBytes; i++) {
            if (network[i] != address[i]) {
                return false;
            }
        }
        int remainingBits = prefix % BITS_PER_BYTE;
        if (remainingBits == 0) {
            return true;
        }
        int mask = (BYTE_MASK << (BITS_PER_BYTE - remainingBits)) & BYTE_MASK;
        return (network[fullBytes] & mask) == (address[fullBytes] & mask);
    }
}
