package ru.ludwigandreas.notification.service;

import java.util.Locale;

/**
 * Masks the two things this service handles that must never appear in full in a log line, a metric
 * tag or a problem detail: a recipient's address and a rendered body.
 *
 * <p>The observability module masks by MDC key as a backstop, and that backstop only fires for keys
 * whose names look sensitive. An email address logged as {@code "sending to {}"} is an interpolated
 * message argument, not an MDC entry, so nothing upstream can catch it - which is why masking here is
 * the actual control and the module's masking is the safety net, not the other way round.
 *
 * <p>Masked rather than removed. {@code j***n@e***.com} is enough for an operator to confirm they
 * are looking at the right delivery when a customer reads their address out over the phone, and not
 * enough to harvest an address list out of a log aggregator the whole company can search.
 */
public final class Pii {

    /** What a value is replaced by when it is too short to mask meaningfully. */
    private static final String FULLY_MASKED = "***";

    /** Characters kept at the start of a local part or a host label. */
    private static final int VISIBLE_PREFIX = 1;

    /** Shortest value that keeps a visible prefix at all; anything shorter is masked entirely. */
    private static final int MIN_MASKABLE_LENGTH = 3;

    private Pii() {
    }

    /**
     * Masks an address of any of the three channels' shapes.
     *
     * <p>One method rather than one per channel, because the call sites are in shared dispatch code
     * that is deliberately channel-agnostic - and a masker that had to be selected per channel is one
     * that gets selected wrongly, in the direction of not masking.
     */
    public static String address(String value) {
        if (value == null || value.isBlank()) {
            return FULLY_MASKED;
        }
        String trimmed = value.trim();
        if (trimmed.indexOf('@') > 0) {
            return email(trimmed);
        }
        if (trimmed.regionMatches(true, 0, "http", 0, "http".length())) {
            return url(trimmed);
        }
        return partial(trimmed);
    }

    /**
     * Reduces a rendered body to its length.
     *
     * <p>A truncated body is still a body: the first eighty characters of a password-reset email
     * contain the recipient's name, and of a one-time-code email frequently contain the code. The
     * only safe summary is one that carries no content at all.
     */
    public static String body(String value) {
        return value == null ? "<null>" : "<" + value.length() + " chars>";
    }

    private static String email(String value) {
        int at = value.indexOf('@');
        String local = value.substring(0, at);
        String domain = value.substring(at + 1);
        int dot = domain.indexOf('.');
        String maskedDomain = dot > 0
                ? partial(domain.substring(0, dot)) + domain.substring(dot)
                : partial(domain);
        return partial(local) + "@" + maskedDomain;
    }

    private static String url(String value) {
        // Host is kept - a callback URL's host is the partner's identity and is not a secret - while
        // the path and query are dropped, because that is where tokens and recipient ids live.
        int schemeEnd = value.indexOf("://");
        int hostStart = schemeEnd < 0 ? 0 : schemeEnd + "://".length();
        int hostEnd = value.indexOf('/', hostStart);
        String host = hostEnd < 0 ? value.substring(hostStart) : value.substring(hostStart, hostEnd);
        return (schemeEnd < 0 ? "" : value.substring(0, hostStart)) + host + "/" + FULLY_MASKED;
    }

    private static String partial(String value) {
        if (value.length() < MIN_MASKABLE_LENGTH) {
            return FULLY_MASKED;
        }
        return value.substring(0, VISIBLE_PREFIX) + FULLY_MASKED;
    }

    /**
     * Masks an address, preserving {@code null} rather than turning it into {@code ***}.
     *
     * <p>Exists so the web mapper can mask without declaring a {@code default String mask(String)} of
     * its own. MapStruct adopts any unannotated {@code String -> String} method on a mapper as an
     * <em>implicit</em> conversion and then applies it to every String property it maps - which
     * silently masked template keys, categories and correlation ids across the whole API. A static
     * method on another class is invisible to that mechanism.
     */
    public static String maskedOrNull(String address) {
        return address == null || address.isBlank() ? null : address(address);
    }

    /**
     * Normalizes an address for the suppression list and for dedup keys.
     *
     * <p>Case folding uses {@link Locale#ROOT} explicitly. In a Turkish locale
     * {@code "I".toLowerCase()} is a dotless i, so a default-locale fold would make a suppression
     * entry written on one node unmatchable on another - a bug that only appears in one region and
     * only for addresses containing an I.
     */
    public static String normalizeAddress(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
