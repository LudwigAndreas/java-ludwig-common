package ru.ludwigandreas.notification.service.model;

import java.util.Locale;
import java.util.Map;

/**
 * One recipient as the caller named them: a user id to resolve, or a literal destination.
 *
 * <p>Both forms exist because both are real. A service notifying its own users should send a
 * {@code userId} and let this service work out the address, the language and the quiet hours - that
 * is what stops six services each keeping their own stale copy of everyone's email. But a partner
 * integration genuinely has an address and no account behind it, and forcing a synthetic user record
 * into existence for it would be worse than accepting the address.
 *
 * @param kind      which of the two forms this is
 * @param userId    the subject, when {@link RecipientKind#USER}
 * @param address   the literal destination, when {@link RecipientKind#ADDRESS}
 * @param locale    language override; normally absent, since the recipient's own profile decides
 * @param timezone  IANA zone override; normally absent, for the same reason
 * @param variables per-recipient template variables merged over the request-wide ones, so one
 *                  request can greet each recipient by name without becoming N requests
 */
public record RecipientRef(
        RecipientKind kind,
        String userId,
        String address,
        Locale locale,
        String timezone,
        Map<String, Object> variables) {

    public RecipientRef {
        if (kind == null) {
            throw new IllegalArgumentException("A recipient must state whether it is a user or an address");
        }
        if (kind == RecipientKind.USER && (userId == null || userId.isBlank())) {
            throw new IllegalArgumentException("A USER recipient needs a userId");
        }
        if (kind == RecipientKind.ADDRESS && (address == null || address.isBlank())) {
            throw new IllegalArgumentException("An ADDRESS recipient needs an address");
        }
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }

    public static RecipientRef ofUser(String userId) {
        return new RecipientRef(RecipientKind.USER, userId, null, null, null, Map.of());
    }

    public static RecipientRef ofAddress(String address) {
        return new RecipientRef(RecipientKind.ADDRESS, null, address, null, null, Map.of());
    }

    /**
     * A label safe to write into a log line, a status-history entry or a problem detail.
     *
     * <p>A user id is a pseudonymous subject and is kept in full - an operator needs to correlate a
     * delivery with a support ticket. An address is personal data and is masked, because every place
     * this string is written outlives the recipient-data retention window: the status trail is kept
     * six months, the logs go to an aggregator the whole company can search, and a problem detail
     * leaves the process entirely.
     */
    public String reference() {
        return kind == RecipientKind.USER
                ? "user:" + userId
                : "address:" + ru.ludwigandreas.notification.service.Pii.address(address);
    }

    /**
     * The value the per-delivery dedup key is built from - unique, and carrying no personal data.
     *
     * <p>The masked {@link #reference()} cannot be used here: masking is lossy by design, so two
     * different addresses can produce the same label and the unique index would reject the second
     * recipient's delivery as a duplicate of the first. The raw address cannot be used either: the
     * dedup key lives on the delivery for the full ninety-day retention, long past the seven days the
     * address itself is kept, so embedding it would quietly defeat the scrub.
     *
     * <p>A hash is both: collision-free in practice and not personal data. It is never read back -
     * nothing needs to recover the address from it - so a one-way function loses nothing.
     */
    public String dedupToken() {
        if (kind == RecipientKind.USER) {
            return "user:" + userId;
        }
        return "address:" + sha256(ru.ludwigandreas.notification.service.Pii.normalizeAddress(address));
    }

    private static String sha256(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            // Mandated by the JDK specification, so unreachable on a conformant runtime.
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
