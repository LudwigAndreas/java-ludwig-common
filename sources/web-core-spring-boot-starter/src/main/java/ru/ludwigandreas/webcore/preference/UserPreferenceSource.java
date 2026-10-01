package ru.ludwigandreas.webcore.preference;

import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import org.springframework.core.Ordered;

/**
 * Somewhere a caller's locale or zone can come from.
 *
 * <h2>Per dimension, not per value</h2>
 *
 * <p>Two methods, each answering {@link Optional}, rather than one answering a whole
 * {@link UserPreferences}. A user who set their timezone and never touched their language is the
 * common case, and a whole-value source would have to invent the other half - at which point a
 * stored zone silently discards the caller's {@code Accept-Language}. Resolving each dimension
 * independently is the only shape in which "stored zone, header locale" is expressible.
 *
 * <p>A source that cannot answer returns {@link Optional#empty()}. It must not answer with a
 * default: a default is itself a source, it is the lowest-priority one, and a source that answers
 * with one makes every source below it unreachable. That is not a hypothetical - it is exactly the
 * mistake {@code StoredUserPreferenceSource} avoids by abstaining on
 * {@code SettingLayer.DEFAULT}, and it would have made every caller in the platform UTC.
 *
 * <h2>Order</h2>
 *
 * <p>Sources are ordered beans and the first to answer a dimension wins it. The three constants
 * below are the positions the shipped sources take, published so that a deployment adding its own
 * can place it <em>relative to them</em> rather than guessing a number - and so that a change to
 * the shipped values moves everybody's source with it.
 *
 * <p>The shipped order is: a stored preference, then the request's headers, then configuration. A
 * stored choice beating a header is the decision worth knowing about:
 * {@code Accept-Language} is a hint the browser sends, a stored locale is a choice the user made,
 * usually precisely because the browser was sending the wrong one. A platform where the browser
 * silently wins has a settings screen that does not work.
 */
public interface UserPreferenceSource extends Ordered {

    /** The position of a stored, per-subject preference: the user's own explicit choice. */
    int STORED_ORDER = 100;

    /** The position of the request's own headers: what the client sent on the caller's behalf. */
    int REQUEST_ORDER = 200;

    /** The position of the deployment's configured defaults, which must always be last. */
    int CONFIGURED_ORDER = Ordered.LOWEST_PRECEDENCE;

    /**
     * This source's answer for the caller's locale.
     *
     * @return the locale, or empty to let the next source answer
     */
    Optional<Locale> locale();

    /**
     * This source's answer for the caller's zone.
     *
     * @return the zone, or empty to let the next source answer
     */
    Optional<ZoneId> zone();

    /**
     * A name for the startup log and for a diagnostic endpoint.
     *
     * <p>Exists because the one failure this package cannot check for - a service that wanted stored
     * preferences and forgot to declare the setting definitions - is invisible in behaviour and
     * obvious in a list of the active sources.
     *
     * @return a short name, the simple class name by default
     */
    default String sourceName() {
        return getClass().getSimpleName();
    }

    @Override
    default int getOrder() {
        return REQUEST_ORDER;
    }
}
