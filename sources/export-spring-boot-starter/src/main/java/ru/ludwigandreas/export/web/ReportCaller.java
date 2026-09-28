package ru.ludwigandreas.export.web;

import java.time.ZoneId;
import java.util.Locale;

/**
 * Who is asking, and in what language.
 *
 * <p>A seam over the security context rather than a call into it, for two reasons that pull the same
 * way. Tests drive the controllers without a filter chain, and a service that identifies callers
 * differently - a partner id, a tenant-scoped subject - replaces one bean instead of forking the
 * controllers. The shipped implementation reads {@code security-spring-boot-starter}'s principal and
 * {@code web-core}'s locale resolution, which is what every other endpoint in a service already uses.
 */
public interface ReportCaller {

    /**
     * The subject a run is attributed to and scoped by.
     *
     * @return the principal's subject; never null - an unauthenticated caller never reaches a
     *         controller, because the filter chain refuses first
     */
    String principalId();

    /** The locale headers and column titles are resolved in. */
    Locale locale();

    /** The zone instants are presented in when the request does not name one. */
    ZoneId zone();
}
