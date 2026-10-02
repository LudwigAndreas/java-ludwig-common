package ru.ludwigandreas.archrules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.archrules.rules.UploadRules;

/**
 * The uploads rule, shown firing and shown staying silent in the same run.
 *
 * <p>The second half matters as much as the first. A rule that caught a hand-written upload endpoint by also
 * catching every controller that happens to handle a file path would be switched off within a quarter, and the
 * defect it exists to prevent would come back with the rule notionally still in place.
 */
class UploadRulesTest {

    @Test
    @DisplayName("a service taking a MultipartFile itself is reported")
    void handRolledUploadIsReported() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("bad.uploads").build()))
                .contains(UploadRules.NO_SECOND_UPLOAD_PATH.value());

        assertThat(Fixtures.violationDescriptions(
                Fixtures.configurationFor("bad.uploads").build(),
                UploadRules.NO_SECOND_UPLOAD_PATH))
                .anyMatch(description -> description.contains("HandRolledUploadController"));
    }

    @Test
    @DisplayName("the remediation names the module to use instead; a refusal with no alternative is ignored")
    void theRemediationNamesTheAlternative() {
        RuleContext context = new RuleContext("test", java.util.List.of("ru.ludwigandreas"),
                java.util.List.of(), ArchitectureConventions.defaults());

        assertThat(new UploadRules().rules(context))
                .singleElement()
                .satisfies(rule -> assertThat(rule.remediation())
                        .contains("file-action-spring-boot-starter")
                        .contains("RowBinding"));
    }

    @Test
    @DisplayName("a module that handles no upload does not break the rule")
    void compliantModuleIsSilent() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("good").build()))
                .doesNotContain(UploadRules.NO_SECOND_UPLOAD_PATH.value());
    }
}
