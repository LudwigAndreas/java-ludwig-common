package ru.ludwigandreas.webcore.preference;

import jakarta.servlet.http.HttpServletRequest;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * What the client sent on the caller's behalf: {@code Accept-Language}, and a timezone header.
 *
 * <h2>There is no standard timezone header</h2>
 *
 * <p>{@code Accept-Language} is RFC 9110. A request header carrying a timezone is not standardised
 * by anything, and the conventions in the wild disagree ({@code X-Timezone}, {@code X-Time-Zone},
 * {@code X-TZ}, a cookie, a query parameter). So the name is configuration -
 * {@code ludwig.web.preferences.time-zone-header}, defaulting to {@code X-Time-Zone} - and this
 * javadoc says it is a convention rather than presenting a constant as a standard. A deployment
 * behind a gateway that already injects one renames it in a property instead of forking this class.
 *
 * <h2>An unusable value is ignored, not rejected</h2>
 *
 * <p>A header carrying {@code Mars/Olympus_Mons}, an empty string or a zone this JVM's tzdata does
 * not know abstains and lets the chain continue. Answering {@code 400} would fail an otherwise
 * valid request over a presentation hint the client did not have to send at all, and the caller has
 * no way to see what they did wrong from an endpoint that was not about timezones.
 *
 * <p>Both a region id ({@code Europe/Moscow}) and a fixed offset ({@code +03:00}) are accepted.
 * A region id is the better answer and the offset form is what a browser can produce without a
 * tzdata lookup, so refusing it would mean refusing most of the clients that bother to send one.
 *
 * <h2>Why the request is taken from the holder</h2>
 *
 * <p>Rather than injected, because this source is also consulted from
 * {@link UserPreferenceLocaleContext}'s lazy resolution, which runs inside a dispatch but is not a
 * controller argument. Off a request thread the holder is empty and this source abstains, which is
 * the correct answer there: a scheduled job has no {@code Accept-Language}.
 */
@Slf4j
public class RequestHeaderPreferenceSource implements UserPreferenceSource {

    private final String timeZoneHeader;

    private final boolean acceptLanguageEnabled;

    /**
     * @param timeZoneHeader        the header name, or null/blank to consult no timezone header
     * @param acceptLanguageEnabled whether {@code Accept-Language} is consulted at all; a deployment
     *                              that resolves locale solely from stored settings switches it off
     *                              rather than relying on clients not to send the header
     */
    public RequestHeaderPreferenceSource(String timeZoneHeader, boolean acceptLanguageEnabled) {
        this.timeZoneHeader = timeZoneHeader == null || timeZoneHeader.isBlank() ? null : timeZoneHeader;
        this.acceptLanguageEnabled = acceptLanguageEnabled;
    }

    @Override
    public Optional<Locale> locale() {
        if (!acceptLanguageEnabled) {
            return Optional.empty();
        }
        return request().flatMap(RequestHeaderPreferenceSource::acceptLanguage);
    }

    @Override
    public Optional<ZoneId> zone() {
        if (timeZoneHeader == null) {
            return Optional.empty();
        }
        return request()
                .map(request -> request.getHeader(timeZoneHeader))
                .flatMap(this::parseZone);
    }

    @Override
    public int getOrder() {
        return REQUEST_ORDER;
    }

    /** The header names this source reads, for the startup log. */
    public List<String> headers() {
        return timeZoneHeader == null ? List.of("Accept-Language") : List.of("Accept-Language", timeZoneHeader);
    }

    private static Optional<Locale> acceptLanguage(HttpServletRequest request) {
        // getLocales() is the parsed, quality-ordered view the servlet container already built from
        // Accept-Language; parsing the raw header again here would be a second, worse parser for a
        // header the container has to parse anyway. An absent header makes it answer the container's
        // default locale, which is the one answer this package must never return - hence the
        // explicit header check rather than trusting getLocale().
        if (request.getHeader("Accept-Language") == null) {
            return Optional.empty();
        }
        Enumeration<Locale> locales = request.getLocales();
        if (locales == null || !locales.hasMoreElements()) {
            return Optional.empty();
        }
        Locale first = locales.nextElement();
        return first == null || first.getLanguage().isEmpty() ? Optional.empty() : Optional.of(first);
    }

    private Optional<ZoneId> parseZone(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ZoneId.of(value.trim(), ZoneId.SHORT_IDS));
        } catch (DateTimeException e) {
            // Debug, not warn: a client sending a zone this JVM does not know is a client problem
            // that the caller cannot act on and that an operator cannot fix, and at warn level one
            // misconfigured client fills the log.
            log.debug("Ignoring unusable {} header value '{}'", timeZoneHeader, value);
            return Optional.empty();
        }
    }

    private static Optional<HttpServletRequest> request() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return Optional.of(attributes.getRequest());
        }
        return Optional.empty();
    }
}
