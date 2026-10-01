package ru.ludwigandreas.archrules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.archrules.rules.PresentationRules;

/**
 * The presentation rules, shown firing and shown staying silent in the same run.
 *
 * <p>Both halves matter and the second one matters more. A rule that caught the second
 * caller-preference type by also catching every per-subject lookup and every scheduling window would
 * be switched off within a quarter, and then the defect it existed to prevent comes back with the
 * rule notionally still in place.
 */
class PresentationRulesTest {

    @Test
    @DisplayName("reading the JVM's default locale or zone is reported")
    void jvmDefaultsAreReported() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("bad.presentation").build()))
                .contains(PresentationRules.NO_AMBIENT_DEFAULT_LOCALE_OR_ZONE.value());

        assertThat(Fixtures.violationDescriptions(
                Fixtures.configurationFor("bad.presentation").build(),
                PresentationRules.NO_AMBIENT_DEFAULT_LOCALE_OR_ZONE))
                .anyMatch(description -> description.contains("SystemZoneRenderer"));
    }

    @Test
    @DisplayName("a restated caller-preference pair is reported; the three shapes that merely resemble it are not")
    void secondCallerPreferenceTypeIsReported() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("bad.presentation").build()))
                .contains(PresentationRules.NO_SECOND_CALLER_PREFERENCE_TYPE.value());

        // The three negatives are the whole value of this assertion, and two of them are regressions
        // rather than hypotheses: the first version of this rule asked only that both fields be present,
        // and the full reactor build reported notification-service's RecipientPreferences and
        // StoredPreferences - the SubjectPreferences shape below - as violations.
        assertThat(Fixtures.violationDescriptions(
                Fixtures.configurationFor("bad.presentation").build(),
                PresentationRules.NO_SECOND_CALLER_PREFERENCE_TYPE))
                .anyMatch(description -> description.contains("CallerContext"))
                .noneMatch(description -> description.contains("RecipientSettings"))
                .noneMatch(description -> description.contains("SubjectPreferences"))
                .noneMatch(description -> description.contains("ScheduleWindow"));
    }

    @Test
    @DisplayName("a module that presents nothing breaks neither rule")
    void compliantModuleIsSilent() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("good").build()))
                .doesNotContain(
                        PresentationRules.NO_AMBIENT_DEFAULT_LOCALE_OR_ZONE.value(),
                        PresentationRules.NO_SECOND_CALLER_PREFERENCE_TYPE.value());
    }
}
