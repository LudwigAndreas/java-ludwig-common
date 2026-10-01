package ru.ludwigandreas.archrules.fixture.bad.presentation.catalog.present;

import java.time.ZoneId;
import java.util.Locale;

/**
 * The same two fields, for a named subject, which is not a violation.
 *
 * <p>The reason the second rule's subject clause exists. These are resolved per recipient on a queue
 * worker, where the request that triggered the fan-out finished long ago and there is no ambient
 * caller to read. An ambient accessor on that path answers with the worker's defaults or with
 * whichever recipient was processed last, so collapsing this into the one contract would be a defect
 * rather than a consolidation - and a rule that flagged it would be suppressed inside the platform
 * that introduced it, which is a rule that does not survive a quarter.
 *
 * @param recipientId whose preferences these are - the field that makes this a lookup result rather
 *                    than an ambient context
 * @param locale      the recipient's language
 * @param zone        the recipient's zone
 */
public record RecipientSettings(String recipientId, Locale locale, ZoneId zone) {
}
