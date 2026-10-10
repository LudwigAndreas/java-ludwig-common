package ru.ludwigandreas.archrules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.archrules.rules.LoggingRules;

/**
 * The logging rules, each shown firing on the shape it governs and staying silent on the shape that
 * merely resembles it.
 *
 * <p>The negatives are the half that keeps the group switched on. A rule that caught the second
 * encoder by also catching every class that formats a string, or the second provenance type by also
 * catching every domain object that records a commit, would be disabled by the first service it
 * annoyed - and then the defect it existed for returns with the rule notionally in place.
 */
class LoggingRulesTest {

    @Test
    @DisplayName("a second encoder and a second layout are reported; a class that only formats text is not")
    void secondLogEncoderIsReported() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("bad.logging").build()))
                .contains(LoggingRules.NO_SECOND_LOG_ENCODER.value());

        assertThat(violations(LoggingRules.NO_SECOND_LOG_ENCODER))
                .anyMatch(description -> description.contains("PipeDelimitedEncoder"))
                .anyMatch(description -> description.contains("AuditLineLayout"))
                .noneMatch(description -> description.contains("LogLineFormatter"));
    }

    @Test
    @DisplayName("the encoder rule is fenced to the observability module and to nothing else")
    void encoderRuleNamesItsOwner() {
        // No fixture can show the exemption, because the exempt package is the real module's. The
        // fence is therefore asserted as what the rule says about itself.
        assertThat(Fixtures.ruleDescription(Fixtures.configurationFor("bad.logging").build(),
                LoggingRules.NO_SECOND_LOG_ENCODER))
                .contains("ru.ludwigandreas.observability..");
    }

    @Test
    @DisplayName("a restated build identity is reported; a domain object carrying a commit is not")
    void secondBuildProvenanceTypeIsReported() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("bad.logging").build()))
                .contains(LoggingRules.NO_SECOND_BUILD_PROVENANCE_TYPE.value());

        assertThat(violations(LoggingRules.NO_SECOND_BUILD_PROVENANCE_TYPE))
                .anyMatch(description -> description.contains("DeploymentInfo"))
                .noneMatch(description -> description.contains("ReleaseNote"))
                .noneMatch(description -> description.contains("CommitReference"));
    }

    @Test
    @DisplayName("launching a process and opening a repository are reported; reading Runtime is not")
    void runtimeRepositoryAccessIsReported() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("bad.logging").build()))
                .contains(LoggingRules.NO_RUNTIME_REPOSITORY_ACCESS.value());

        assertThat(violations(LoggingRules.NO_RUNTIME_REPOSITORY_ACCESS))
                .anyMatch(description -> description.contains("GitDescribe.head"))
                .anyMatch(description -> description.contains("LegacyGitDescribe"))
                .anyMatch(description -> description.contains("RepositoryReader"))
                .noneMatch(description -> description.contains("WorkerPoolSizer"));
    }

    @Test
    @DisplayName("a module that shapes no log output and launches nothing breaks none of the three")
    void compliantModuleIsSilent() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("good").build()))
                .doesNotContain(
                        LoggingRules.NO_SECOND_LOG_ENCODER.value(),
                        LoggingRules.NO_SECOND_BUILD_PROVENANCE_TYPE.value(),
                        LoggingRules.NO_RUNTIME_REPOSITORY_ACCESS.value());
    }

    @Test
    @DisplayName("the group is on without being asked for")
    void groupIsEnabledByDefault() {
        assertThat(RuleGroup.LOGGING.enabledByDefault()).isTrue();
        assertThat(Fixtures.resolvedRuleIds(Fixtures.configurationFor("good").build()))
                .contains(
                        LoggingRules.NO_SECOND_LOG_ENCODER.value(),
                        LoggingRules.NO_SECOND_BUILD_PROVENANCE_TYPE.value(),
                        LoggingRules.NO_RUNTIME_REPOSITORY_ACCESS.value());
    }

    private List<String> violations(RuleId id) {
        return Fixtures.violationDescriptions(Fixtures.configurationFor("bad.logging").build(), id);
    }
}
