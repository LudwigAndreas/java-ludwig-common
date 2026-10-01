package ru.ludwigandreas.webcore.integration;

import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.webcore.preference.UserPreferenceFormatter;
import ru.ludwigandreas.webcore.preference.UserPreferences;

/**
 * A controller that renders one instant four ways, so the integration test can tell which of them
 * the starter actually fixed.
 *
 * <p>The {@code holderZone} field is the interesting one. It is what a {@code @JsonFormat} without an
 * explicit zone, a {@code @DateTimeFormat} conversion and every pre-existing
 * {@code LocaleContextHolder} caller in the platform reads - so if it does not change, the claim
 * that this contract reaches existing code with no edit is false.
 */
@RestController
public class TestPreferenceController {

    /** A fixed instant, so the assertions are about the zone rather than about the clock. */
    static final Instant FIXED = Instant.parse("2024-05-31T20:30:00Z");

    private final UserPreferenceFormatter formatter;

    public TestPreferenceController(UserPreferenceFormatter formatter) {
        this.formatter = formatter;
    }

    /** The four views of one instant. */
    @GetMapping("/preferences")
    public PreferenceView view() {
        UserPreferences preferences = UserPreferences.current();
        return new PreferenceView(
                preferences.locale().toLanguageTag(),
                preferences.zone().getId(),
                LocaleContextHolder.getTimeZone().getID(),
                formatter.toUserOffsetDateTime(FIXED),
                formatter.toUserLocalDate(FIXED).toString(),
                formatter.formatDecimal(new java.math.BigDecimal("1234.56")));
    }

    /**
     * What the controller answers.
     *
     * @param locale        the resolved locale
     * @param zone          the resolved zone
     * @param holderZone    what Spring's own holder reports, which is the regression this guards
     * @param mapped        the instant as a mapper would convert it
     * @param mappedDate    the calendar date a mapper would convert it to
     * @param mappedDecimal a decimal as a mapper would render it
     */
    public record PreferenceView(String locale, String zone, String holderZone,
                                 OffsetDateTime mapped, String mappedDate, String mappedDecimal) {
    }
}
