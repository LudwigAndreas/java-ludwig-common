package ru.ludwigandreas.webcore.preference;

import java.text.NumberFormat;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.WeekFields;
import java.util.Locale;
import java.util.Optional;
import java.util.TimeZone;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.i18n.TimeZoneAwareLocaleContext;

/**
 * The caller's presentation preferences: what language to address them in, and what zone to render
 * their times in.
 *
 * <h2>Two dimensions, and why not three</h2>
 *
 * <p>A locale and a zone, both non-null, and nothing else. Everything a presentation path actually
 * asks for past those two is a <em>function</em> of them - a decimal separator, a first day of week,
 * a short date pattern - and a function belongs on this record rather than beside it. A separately
 * stored {@code firstDayOfWeek} can disagree with the stored locale, and then two correct-looking
 * reads of the same user produce a calendar that starts on Monday with Sunday's column highlighted.
 *
 * <p>A preference that genuinely does not derive from either - a digest cadence, a default currency,
 * a landing page - is a {@code SettingDefinition} in {@code user-settings-spring-boot-starter}, read
 * by the module that cares. It is not a field here. Two dimensions that cannot be derived from one
 * another is a value object; five is a settings store, and the platform has one of those.
 *
 * <h2>The ambient read</h2>
 *
 * <p>{@link #current()} is a static read, for the same reason
 * {@code ru.ludwigandreas.security.principal.SecurityPrincipals} is one: the same lookup has to work
 * from a MapStruct-generated mapper, a JPA entity listener and a repository fragment, none of which
 * is a good place to thread a service through. An injected resolver would be the tidier shape and
 * would not be reachable from the three call sites that need it most, which is how
 * {@code ZoneId.systemDefault()} comes to be called instead.
 *
 * <p>Unlike {@code SecurityPrincipals.require()}, {@link #current()} never throws. The asymmetry is
 * deliberate: an unscoped query is a data leak, so throwing is right there, while an unresolved
 * presentation preference has a correct conservative answer and throwing would turn a cosmetic gap
 * into a 500 on a path that was only formatting a date. Code that needs to know whether a real
 * caller was resolved asks {@link #currentIfResolved()}.
 *
 * @param locale the language the caller is addressed in
 * @param zone   the zone the caller's times are rendered in - never the container's
 */
public record UserPreferences(Locale locale, ZoneId zone) {

    /**
     * What {@link #current()} answers when nothing has been resolved, bound or configured.
     *
     * <p>UTC rather than the container's zone, for the reason that is written in three other places
     * in this repository and is the whole point of this type: a server-zone default is invisible in
     * development, where the two coincide, and wrong in production for every caller who is not in
     * the datacentre's timezone - and it changes meaning when the service is deployed elsewhere.
     */
    public static final UserPreferences FALLBACK = new UserPreferences(Locale.ENGLISH, ZoneOffset.UTC);

    public UserPreferences {
        if (locale == null) {
            throw new IllegalArgumentException("UserPreferences needs a locale");
        }
        if (zone == null) {
            throw new IllegalArgumentException("UserPreferences needs a zone");
        }
    }

    /**
     * The preferences in force on this thread.
     *
     * <p>Resolution order is: the {@link LocaleContext} this package published, then any other
     * timezone-aware context, then the deployment's configured defaults, then {@link #FALLBACK}.
     * The first branch exists so that a zone survives the round trip exactly: Spring's holder
     * carries a {@link TimeZone}, and {@code TimeZone.getTimeZone(zone).toZoneId()} does not always
     * return the {@link ZoneId} it was given.
     *
     * @return the current preferences; never null
     */
    public static UserPreferences current() {
        LocaleContext context = LocaleContextHolder.getLocaleContext();
        if (context instanceof UserPreferenceLocaleContext carrier) {
            return carrier.preferences();
        }
        Locale locale = context == null ? null : context.getLocale();
        ZoneId zone = null;
        if (context instanceof TimeZoneAwareLocaleContext aware
                && aware.getTimeZone() != null) {
            zone = aware.getTimeZone().toZoneId();
        }
        UserPreferences defaults = UserPreferenceDefaults.get();
        return new UserPreferences(
                locale == null ? defaults.locale() : locale,
                zone == null ? defaults.zone() : zone);
    }

    /**
     * The preferences in force on this thread, or empty when none were resolved or bound.
     *
     * <p>For code that must not claim a preference nobody expressed - an audit enricher recording
     * the caller's zone, which should record nothing rather than record the deployment's default as
     * though the caller had chosen it.
     *
     * @return the bound preferences, or empty
     */
    public static Optional<UserPreferences> currentIfResolved() {
        LocaleContext context = LocaleContextHolder.getLocaleContext();
        if (context instanceof UserPreferenceLocaleContext carrier) {
            return Optional.of(carrier.preferences());
        }
        return Optional.empty();
    }

    /**
     * Binds these preferences to the current thread until the returned scope is closed.
     *
     * <p>What an asynchronous handoff needs. A request thread captures {@link #current()} into the
     * run it is accepting; the worker executing that run binds it for the duration. Without this,
     * the worker renders in whatever the pool's last task left behind or in the container's zone,
     * which is the bug class that cannot be reproduced on a developer's machine because there the
     * two agree.
     *
     * <p>Closing restores the thread's previous context, <em>including when there was none</em> -
     * otherwise a pooled thread carries one caller's preferences into the next caller's task.
     *
     * @return a scope to close on the same thread, in a try-with-resources
     */
    public Scope bind() {
        LocaleContext previous = LocaleContextHolder.getLocaleContext();
        LocaleContextHolder.setLocaleContext(new UserPreferenceLocaleContext(this));
        return () -> LocaleContextHolder.setLocaleContext(previous);
    }

    /** These preferences as a {@link LocaleContext}, for code wiring Spring's holder directly. */
    public LocaleContext asLocaleContext() {
        return new UserPreferenceLocaleContext(this);
    }

    /** The zone as a {@link TimeZone}, which is what Spring's holder and the legacy APIs carry. */
    public TimeZone timeZone() {
        return TimeZone.getTimeZone(zone);
    }

    /**
     * An instant as an offset date-time in the caller's zone.
     *
     * <p>The conversion a DTO wants: an {@code OffsetDateTime} serializes with the offset the caller
     * reads times in, so a client rendering it without its own timezone logic is still right.
     *
     * @param instant the instant; null in, null out, because a mapper maps absent fields too
     * @return the instant in this caller's zone, or null
     */
    public OffsetDateTime at(Instant instant) {
        return instant == null ? null : instant.atZone(zone).toOffsetDateTime();
    }

    /**
     * The calendar date an instant falls on for this caller.
     *
     * <p>Not derivable from the instant alone, and the reason this type exists: 2024-01-01T02:00Z is
     * the 1st in London and the 31st of December in New York, so a "created on" column rendered
     * without the caller's zone is off by a day for everybody west of the datacentre.
     *
     * @param instant the instant; null in, null out
     * @return the local date in this caller's zone, or null
     */
    public LocalDate dateAt(Instant instant) {
        return instant == null ? null : instant.atZone(zone).toLocalDate();
    }

    /**
     * A date-time formatter bound to this caller's locale and zone.
     *
     * @param style the length - {@code SHORT} for a table cell, {@code MEDIUM} for a detail page
     * @return a formatter that can format an {@link Instant} directly
     */
    public DateTimeFormatter dateTimeFormatter(FormatStyle style) {
        return DateTimeFormatter.ofLocalizedDateTime(style).withLocale(locale).withZone(zone);
    }

    /**
     * A date-only formatter bound to this caller's locale and zone.
     *
     * @param style the length
     * @return a formatter that can format an {@link Instant} directly
     */
    public DateTimeFormatter dateFormatter(FormatStyle style) {
        return DateTimeFormatter.ofLocalizedDate(style).withLocale(locale).withZone(zone);
    }

    /**
     * A number format for this caller's locale.
     *
     * <p>A fresh instance per call, because {@link NumberFormat} is mutable and not thread-safe, and
     * a cached one shared across request threads is a corrupted-output bug that appears only under
     * load.
     *
     * @return a number format for this locale
     */
    public NumberFormat numberFormat() {
        return NumberFormat.getInstance(locale);
    }

    /**
     * The day this caller's week starts on.
     *
     * <p>Derived from the locale rather than stored, so it cannot disagree with it. Monday in Russia
     * and most of Europe, Sunday in the United States - which is why a hard-coded
     * {@code DayOfWeek.MONDAY} in a date picker is wrong for half the platform's users.
     *
     * <p><b>The region carries this, not the language.</b> The JDK holds first-day-of-week as region
     * data, so {@code WeekFields.of(Locale.forLanguageTag("ru"))} answers {@code SUNDAY} while
     * {@code ru-RU} answers {@code MONDAY}. A locale that arrived without a region therefore gets the
     * CLDR root's answer rather than the one its speakers expect. That is why
     * {@code UserPreferenceResolver} resolves to the caller's own tag rather than narrowing it to the
     * supported language, and why a stored {@code user.locale} is worth storing with its region.
     *
     * @return the first day of the week for this locale
     */
    public DayOfWeek firstDayOfWeek() {
        return WeekFields.of(locale).getFirstDayOfWeek();
    }

    /**
     * A binding of preferences to a thread, closed on the thread that opened it.
     *
     * <p>Narrowed from {@link AutoCloseable} so that {@code close()} declares no checked exception:
     * restoring a thread-local cannot fail, and a {@code throws Exception} would force every
     * try-with-resources in the platform to catch something that never happens.
     */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {

        @Override
        void close();
    }
}
