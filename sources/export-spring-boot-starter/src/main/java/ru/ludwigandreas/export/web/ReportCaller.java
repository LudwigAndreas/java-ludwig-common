package ru.ludwigandreas.export.web;

import java.time.ZoneId;
import java.util.Locale;
import ru.ludwigandreas.webcore.preference.UserPreferences;

/**
 * Who is asking, and in what language and zone.
 *
 * <p>A seam over the security context rather than a call into it, for two reasons that pull the same
 * way. Tests drive the controllers without a filter chain, and a service that identifies callers
 * differently - a partner id, a tenant-scoped subject - replaces one bean instead of forking the
 * controllers. The shipped implementation reads {@code security-spring-boot-starter}'s principal and
 * {@code web-core}'s caller-preference contract, which is what every other endpoint in a service
 * already uses.
 *
 * <h2>One method for the presentation pair</h2>
 *
 * <p>{@link #locale()} and {@link #zone()} are defaults over {@link #preferences()} rather than
 * three independent methods. Three would let an implementation answer a locale and a zone that
 * disagree with the pair it reports - and the report is what a run is attributed with, so the file
 * and the trail would be rendered differently. One method, two views.
 */
public interface ReportCaller {

    /**
     * The subject a run is attributed to and scoped by.
     *
     * @return the principal's subject; never null - an unauthenticated caller never reaches a
     *         controller, because the filter chain refuses first
     */
    String principalId();

    /**
     * The caller's presentation preferences, from {@code web-core}'s one contract.
     *
     * @return the caller's locale and zone; never null
     */
    UserPreferences preferences();

    /** The locale headers and column titles are resolved in. */
    default Locale locale() {
        return preferences().locale();
    }

    /** The zone instants are presented in when the request does not name one. */
    default ZoneId zone() {
        return preferences().zone();
    }
}
