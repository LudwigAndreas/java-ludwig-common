package ru.ludwigandreas.notification.service.preference;

import java.time.ZoneId;
import java.util.Locale;

/**
 * What a preference store knows about one recipient, in this service's own vocabulary.
 *
 * <p>Every field is nullable on purpose except the matrix: "the store has no opinion" and "the store
 * says English" are different facts, and only the first may be overridden by the request's hints or
 * by configuration. Collapsing them - defaulting inside the source - would make a recipient who
 * deliberately chose English indistinguishable from one who chose nothing, and the two resolve
 * differently the moment a caller supplies a locale of its own.
 *
 * @param locale     the recipient's chosen language, or null if they have not chosen one
 * @param zone       the zone their quiet hours are judged in, or null
 * @param quietHours their window; never null, {@link QuietHoursWindow#none()} when unset
 * @param digest     how they want batching done, or null when they have not said
 * @param optOuts    their opt-out answers; never null, {@link OptOutMatrix#NONE} when there are none
 */
public record StoredPreferences(
        Locale locale,
        ZoneId zone,
        QuietHoursWindow quietHours,
        DigestMode digest,
        OptOutMatrix optOuts) {

    private static final StoredPreferences NONE =
            new StoredPreferences(null, null, QuietHoursWindow.none(), null, OptOutMatrix.NONE);

    public StoredPreferences {
        quietHours = quietHours == null ? QuietHoursWindow.none() : quietHours;
        optOuts = optOuts == null ? OptOutMatrix.NONE : optOuts;
    }

    /**
     * Nothing stored about this recipient.
     *
     * <p>The answer for a literal address with no account behind it, for a deployment with no
     * preference store wired in, and for a store that simply has no row yet. All three resolve to
     * the configured defaults and to "not opted out", which is the only safe direction: a preference
     * that has not arrived must not silence a person.
     */
    public static StoredPreferences none() {
        return NONE;
    }
}
