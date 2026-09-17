package ru.ludwigandreas.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.notification.repository.RecipientPreferenceRepository;
import ru.ludwigandreas.notification.repository.entity.ChannelKind;
import ru.ludwigandreas.notification.repository.entity.RecipientPreferenceEntity;
import ru.ludwigandreas.notification.service.model.CategoryClass;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.preference.DispatchDecision;
import ru.ludwigandreas.notification.service.preference.PreferenceEvaluator;
import ru.ludwigandreas.notification.service.recipient.QuietHours;
import ru.ludwigandreas.notification.service.recipient.ResolvedRecipient;

/**
 * The opt-out rules, the transactional bypass, and the precedence between the four rows that can
 * bear on one decision.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PreferenceEvaluatorTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final String USER = "user-1";
    private static final String CATEGORY = "order-updates";

    @Mock
    private RecipientPreferenceRepository preferences;

    private NotificationProperties properties;
    private PreferenceEvaluator evaluator;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        evaluator = new PreferenceEvaluator(preferences, properties);
    }

    @Test
    @DisplayName("with no preference rows at all, a marketing notification is allowed")
    void absenceMeansAllowed() {
        when(preferences.findApplicable(anyString(), anyString(), any())).thenReturn(List.of());

        assertThat(evaluate(CategoryClass.MARKETING, daytime()))
                .isInstanceOf(DispatchDecision.Allowed.class);
    }

    @Test
    @DisplayName("an exact opt-out suppresses")
    void exactOptOutSuppresses() {
        when(preferences.findApplicable(anyString(), anyString(), any()))
                .thenReturn(List.of(preference(CATEGORY, ChannelKind.EMAIL, false)));

        DispatchDecision decision = evaluate(CategoryClass.MARKETING, daytime());

        assertThat(decision).isInstanceOf(DispatchDecision.Suppressed.class);
        assertThat(((DispatchDecision.Suppressed) decision).reason())
                .isEqualTo(DispatchDecision.Reasons.OPT_OUT);
    }

    /**
     * The distinction that makes opt-out implementable at all. A recipient who unsubscribed from
     * everything must still receive their password reset, and a single on/off switch cannot express
     * that - so the bypass is a property of the category rather than a flag on the request.
     */
    @Test
    @DisplayName("a transactional notification ignores even a total opt-out")
    void transactionalBypassesPreferences() {
        when(preferences.findApplicable(anyString(), anyString(), any()))
                .thenReturn(List.of(preference(PreferenceEvaluator.WILDCARD_CATEGORY, null, false)));

        assertThat(evaluate(CategoryClass.TRANSACTIONAL, daytime()))
                .isInstanceOf(DispatchDecision.Allowed.class);
    }

    /**
     * "Nothing at all, except order updates by email" is two rows, and the more specific one wins -
     * which is what lets a recipient express it without the record of their original request being
     * deleted.
     */
    @Test
    @DisplayName("an exact opt-in beats a wildcard opt-out")
    void specificityWins() {
        when(preferences.findApplicable(anyString(), anyString(), any())).thenReturn(List.of(
                preference(PreferenceEvaluator.WILDCARD_CATEGORY, null, false),
                preference(CATEGORY, ChannelKind.EMAIL, true)));

        assertThat(evaluate(CategoryClass.MARKETING, daytime()))
                .isInstanceOf(DispatchDecision.Allowed.class);
    }

    @Test
    @DisplayName("a category-wide opt-out beats a total opt-in")
    void categoryBeatsWildcard() {
        when(preferences.findApplicable(anyString(), anyString(), any())).thenReturn(List.of(
                preference(PreferenceEvaluator.WILDCARD_CATEGORY, null, true),
                preference(CATEGORY, null, false)));

        assertThat(evaluate(CategoryClass.MARKETING, daytime()))
                .isInstanceOf(DispatchDecision.Suppressed.class);
    }

    @Test
    @DisplayName("a channel-specific row beats an all-channels row for the same category")
    void channelBeatsAllChannels() {
        when(preferences.findApplicable(anyString(), anyString(), any())).thenReturn(List.of(
                preference(CATEGORY, null, false),
                preference(CATEGORY, ChannelKind.EMAIL, true)));

        assertThat(evaluate(CategoryClass.MARKETING, daytime()))
                .isInstanceOf(DispatchDecision.Allowed.class);
    }

    /**
     * Deferring rather than dropping: the caller was told the request was accepted, so something has
     * to arrive. Dropping is simpler and silently lossy.
     */
    @Test
    @DisplayName("quiet hours defer to the end of the window rather than suppressing")
    void quietHoursDefer() {
        when(preferences.findApplicable(anyString(), anyString(), any())).thenReturn(List.of());

        DispatchDecision decision = evaluate(CategoryClass.MARKETING, night());

        assertThat(decision).isInstanceOf(DispatchDecision.Deferred.class);
        DispatchDecision.Deferred deferred = (DispatchDecision.Deferred) decision;
        assertThat(deferred.reason()).isEqualTo(DispatchDecision.Reasons.QUIET_HOURS);
        assertThat(ZonedDateTime.ofInstant(deferred.notBefore(), BERLIN).toLocalTime())
                .isEqualTo(LocalTime.of(7, 0));
    }

    @Test
    @DisplayName("quiet hours suppress instead when deferral is switched off")
    void quietHoursCanSuppress() {
        properties.getPreferences().setQuietHoursDefer(false);
        when(preferences.findApplicable(anyString(), anyString(), any())).thenReturn(List.of());

        assertThat(evaluate(CategoryClass.MARKETING, night()))
                .isInstanceOf(DispatchDecision.Suppressed.class);
    }

    @Test
    @DisplayName("a transactional notification goes out during quiet hours")
    void transactionalIgnoresQuietHours() {
        assertThat(evaluate(CategoryClass.TRANSACTIONAL, night()))
                .isInstanceOf(DispatchDecision.Allowed.class);
    }

    @Test
    @DisplayName("quiet hours do nothing when the feature is switched off globally")
    void quietHoursCanBeDisabled() {
        properties.getPreferences().setQuietHoursEnabled(false);
        when(preferences.findApplicable(anyString(), anyString(), any())).thenReturn(List.of());

        assertThat(evaluate(CategoryClass.MARKETING, night()))
                .isInstanceOf(DispatchDecision.Allowed.class);
    }

    /**
     * A literal address has no subject, so there is nothing to look preferences up by - and searching
     * the profile table by address would let a caller who guessed one inherit that person's settings.
     */
    @Test
    @DisplayName("a recipient with no user id is not subject to preferences")
    void addressOnlyRecipientHasNoPreferences() {
        ResolvedRecipient anonymous = new ResolvedRecipient(null, ChannelType.EMAIL,
                "someone@example.com", Locale.ENGLISH, BERLIN, null, null, false,
                QuietHours.none(BERLIN));

        assertThat(evaluator.evaluate(anonymous, CATEGORY, CategoryClass.MARKETING, daytime()))
                .isInstanceOf(DispatchDecision.Allowed.class);
    }

    private DispatchDecision evaluate(CategoryClass categoryClass, Instant at) {
        return evaluator.evaluate(recipient(), CATEGORY, categoryClass, at);
    }

    private ResolvedRecipient recipient() {
        return new ResolvedRecipient(USER, ChannelType.EMAIL, "user@example.com", Locale.ENGLISH,
                BERLIN, "A User", "acme", true,
                new QuietHours(LocalTime.of(22, 0), LocalTime.of(7, 0), BERLIN));
    }

    private static RecipientPreferenceEntity preference(String category, ChannelKind channel,
                                                        boolean allowed) {
        RecipientPreferenceEntity entity = RecipientPreferenceEntity.builder()
                .userId(USER)
                .category(category)
                .channel(channel)
                .allowed(allowed)
                .source("test")
                .build();
        // The tie-break between two equally specific rows is the id, so it has to be set for the
        // comparator to be total - and a null here would be an NPE rather than a test failure.
        entity.setId(UUID.randomUUID());
        return entity;
    }

    private static Instant daytime() {
        return ZonedDateTime.of(2026, 3, 10, 14, 0, 0, 0, BERLIN).toInstant();
    }

    private static Instant night() {
        return ZonedDateTime.of(2026, 3, 10, 23, 30, 0, 0, BERLIN).toInstant();
    }
}
