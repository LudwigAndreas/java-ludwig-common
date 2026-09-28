package ru.ludwigandreas.usersettings.consent;

import java.time.Instant;
import lombok.Builder;

/**
 * What is being recorded when a subject agrees to, or withdraws from, a consent.
 *
 * <p>The evidence fields are supplied by the caller rather than read from an ambient request. This
 * module has no hard dependency on the servlet stack - it runs in queue workers and in services with
 * no REST layer at all - and a service recording a consent from a mobile API, an operator screen or
 * a paper form has a different idea of what "the request context" is. The shipped REST controller
 * fills them from the HTTP request; anything else fills them from whatever it has.
 *
 * @param consentKey        which consent - {@code marketing.email}, {@code terms-of-service}
 * @param textVersion       the version of the wording that was shown. Required, because without it
 *                          the record answers "did they agree" and not "to what"
 * @param locale            which translation was presented, as a BCP 47 tag
 * @param occurredAt        when the person decided; defaults to now when not supplied, which is
 *                          correct for an interactive grant and wrong for one being back-filled
 * @param evidenceIp        the client address the decision arrived from
 * @param evidenceUserAgent the client the decision arrived from
 */
@Builder
public record ConsentGrant(
        String consentKey,
        String textVersion,
        String locale,
        Instant occurredAt,
        String evidenceIp,
        String evidenceUserAgent) {

    /** Rejects a decision that names no consent or no version of the text it was made against. */
    public ConsentGrant {
        if (consentKey == null || consentKey.isBlank()) {
            throw new IllegalArgumentException("A consent decision needs a consent key");
        }
        if (textVersion == null || textVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "A consent decision needs the version of the text that was agreed to; without it the"
                            + " record cannot answer what the subject actually consented to");
        }
    }
}
