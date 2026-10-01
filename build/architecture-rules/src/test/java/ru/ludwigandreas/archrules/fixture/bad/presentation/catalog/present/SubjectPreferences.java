package ru.ludwigandreas.archrules.fixture.bad.presentation.catalog.present;

import java.time.ZoneId;
import java.util.Locale;

/**
 * The pair carried alongside a subject's own state, which is not a violation.
 *
 * <p>Modelled directly on {@code notification-service}'s {@code RecipientPreferences}, and in the
 * fixture because the first version of this rule flagged it. The rule used to ask only that both
 * fields be present; the full reactor build reported this shape in two classes, and the discriminator
 * became "the declared fields are exactly the pair and nothing else". A type that carries the pair
 * <em>plus its own state</em> is a domain object that needs a locale and a zone, which is every
 * legitimate holder of them.
 *
 * @param locale     the subject's language
 * @param zone       the subject's zone
 * @param digest     how often they want batched messages - the field that makes this the subject's
 *                   own state rather than an ambient context
 * @param optedOut   whether they declined
 */
public record SubjectPreferences(Locale locale, ZoneId zone, String digest, boolean optedOut) {
}
