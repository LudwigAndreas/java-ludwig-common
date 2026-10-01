package ru.ludwigandreas.webcore.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.webcore.preference.UserPreferenceFormatter;
import ru.ludwigandreas.webcore.preference.UserPreferences;

/**
 * The mapper-facing conversions, and the structural constraint that makes MapStruct able to select
 * them without a qualifier.
 */
class UserPreferenceFormatterTest {

    private static final ZoneId YEKATERINBURG = ZoneId.of("Asia/Yekaterinburg");

    private final UserPreferenceFormatter formatter = new UserPreferenceFormatter();

    @Test
    @DisplayName("an instant is converted at the caller's offset, not the container's")
    void convertsAtTheCallersOffset() {
        try (UserPreferences.Scope scope = bound()) {
            OffsetDateTime converted = formatter.toUserOffsetDateTime(Instant.parse("2024-06-01T10:00:00Z"));

            assertThat(converted.getOffset().getTotalSeconds()).isEqualTo(5 * 3600);
            assertThat(converted.getHour()).isEqualTo(15);
        }
    }

    @Test
    @DisplayName("the local date is the caller's, which is a different day either side of midnight")
    void convertsToTheCallersLocalDate() {
        try (UserPreferences.Scope scope = bound()) {
            assertThat(formatter.toUserLocalDate(Instant.parse("2024-05-31T20:00:00Z")))
                    .isEqualTo(LocalDate.of(2024, 6, 1));
        }
    }

    @Test
    @DisplayName("a decimal carries the caller's separators")
    void formatsADecimal() {
        try (UserPreferences.Scope scope = bound()) {
            assertThat(formatter.formatDecimal(new BigDecimal("1234.56"))).endsWith(",56");
        }
    }

    @Test
    @DisplayName("null in, null out, on every conversion")
    void passesNullThrough() {
        try (UserPreferences.Scope scope = bound()) {
            assertThat(formatter.toUserOffsetDateTime(null)).isNull();
            assertThat(formatter.toUserLocalDate(null)).isNull();
            assertThat(formatter.formatInstant(null)).isNull();
            assertThat(formatter.formatDecimal(null)).isNull();
        }
    }

    /**
     * The rule the formatter's javadoc states and that nothing else can check.
     *
     * <p>MapStruct selects an unannotated {@code uses} method by its source and target types alone, so
     * two single-argument methods sharing a pair are an ambiguity the <em>consuming</em> module has to
     * resolve - and resolving it means annotating this class with {@code org.mapstruct.Named}, which
     * would make MapStruct a dependency of {@code web-core}. ArchUnit cannot see that two methods are
     * MapStruct-ambiguous and Checkstyle sees source text rather than resolved types, so the check
     * lives here, in the module that owns the class, as a reflective assertion over its own surface.
     */
    @Test
    @DisplayName("exactly one method per source/target pair, or MapStruct cannot select without a qualifier")
    void declaresOneMethodPerTypePair() {
        Set<String> pairs = new HashSet<>();

        for (Method method : UserPreferenceFormatter.class.getDeclaredMethods()) {
            if (method.isSynthetic() || method.getParameterCount() != 1) {
                continue;
            }
            String pair = method.getParameterTypes()[0].getName() + " -> " + method.getReturnType().getName();
            assertThat(pairs.add(pair))
                    .as("a second single-argument method for %s - MapStruct would report an ambiguity in "
                            + "every consuming module", pair)
                    .isTrue();
        }

        assertThat(pairs).isNotEmpty();
    }

    private static UserPreferences.Scope bound() {
        return new UserPreferences(Locale.forLanguageTag("ru-RU"), YEKATERINBURG).bind();
    }
}
