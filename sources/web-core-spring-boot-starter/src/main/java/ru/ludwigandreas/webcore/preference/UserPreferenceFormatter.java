package ru.ludwigandreas.webcore.preference;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.FormatStyle;

/**
 * The conversions a mapper needs, as a bean a mapper can name.
 *
 * <h2>How a mapper uses this</h2>
 *
 * <pre>{@code
 * @Mapper(componentModel = "spring", uses = UserPreferenceFormatter.class)
 * public interface ProductMapper {
 *     ProductResponse toResponse(Product product);   // Instant createdAt -> OffsetDateTime createdAt
 * }
 * }</pre>
 *
 * <p>That is MapStruct's own mechanism, not a convention invented here: an unmapped
 * {@code Instant -> OffsetDateTime} is resolved through a single-argument method on a {@code uses}
 * type automatically. No mapping method signature changes, no {@code @Mapping} annotation is added,
 * and nothing has to be threaded through the service layer - which is what the alternative was, and
 * why the alternative was instead a call to {@code ZoneId.systemDefault()}.
 *
 * <h2>Exactly one method per source/target pair - and no check can see that</h2>
 *
 * <p>This class deliberately declares one method per (source type, target type) pair. MapStruct
 * selects an unannotated {@code uses} method by its types alone; two candidates for one pair is a
 * compile error in the <em>consuming</em> module, which then has to disambiguate with
 * {@code qualifiedByName} - and that would require annotating this class with
 * {@code org.mapstruct.Named}, which would make MapStruct a dependency of {@code web-core} for the
 * sake of a type that is useful without it.
 *
 * <p>So the rule is: never add a second single-argument method with the same source and target type
 * to this class. There is no mechanical check for it. ArchUnit cannot know that two methods on a
 * class are MapStruct-ambiguous, and Checkstyle sees source text rather than resolved types. The
 * acceptable half of the trade is that breaking the rule fails the consuming module's build with a
 * message naming both candidates, rather than silently picking one. A service that needs a second
 * rendering of the same pair writes its own {@code @Named} wrapper in its own mapper, which is where
 * that annotation belongs.
 *
 * <h2>Why a bean at all, when UserPreferences.current() is static</h2>
 *
 * <p>Because {@code uses} needs a type with methods for MapStruct to select, and because a bean is
 * replaceable: a service can define its own {@code UserPreferenceFormatter} to change how a date is
 * rendered platform-wide without touching a mapper. Every method here reads
 * {@link UserPreferences#current()}, so the bean holds no state and is safe to share.
 */
public class UserPreferenceFormatter {

    /**
     * An instant in the caller's zone. The automatic choice for an {@code Instant} field on a DTO.
     *
     * @param instant the instant; null in, null out
     * @return the instant at the caller's offset, or null
     */
    public OffsetDateTime toUserOffsetDateTime(Instant instant) {
        return UserPreferences.current().at(instant);
    }

    /**
     * The calendar date an instant falls on for the caller.
     *
     * <p>A separate method rather than a flag, because it is a different target type and so a
     * different MapStruct selection: a DTO declaring {@code LocalDate} gets this one and a DTO
     * declaring {@code OffsetDateTime} gets the one above, with nothing to configure.
     *
     * @param instant the instant; null in, null out
     * @return the local date in the caller's zone, or null
     */
    public LocalDate toUserLocalDate(Instant instant) {
        return UserPreferences.current().dateAt(instant);
    }

    /**
     * An instant as text, in the caller's locale and zone, at medium length.
     *
     * <p>The only {@code Instant -> String} method here, which is what lets MapStruct select it
     * without a qualifier. Medium rather than short because a short form drops the year in several
     * locales, and a timestamp without a year in an audit column is unreadable a January later.
     *
     * @param instant the instant; null in, null out
     * @return the formatted instant, or null
     */
    public String formatInstant(Instant instant) {
        if (instant == null) {
            return null;
        }
        UserPreferences preferences = UserPreferences.current();
        return preferences.dateTimeFormatter(FormatStyle.MEDIUM).format(instant);
    }

    /**
     * A decimal as text, in the caller's locale.
     *
     * <p>The separator is the whole point: {@code 1 234,56} in Russian and {@code 1,234.56} in
     * English are the same number, and a response that renders the second to a Russian-locale caller
     * is read as a different quantity rather than as a formatting quirk.
     *
     * @param value the value; null in, null out
     * @return the formatted number, or null
     */
    public String formatDecimal(BigDecimal value) {
        return value == null ? null : UserPreferences.current().numberFormat().format(value);
    }
}
