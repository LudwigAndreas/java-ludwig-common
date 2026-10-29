package ru.ludwigandreas.archrules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.archrules.rules.AdminRules;

/**
 * Each administrative rule is shown to fire on a deliberately violating fixture and to stay silent on the correct
 * shape beside it. A rule with no failing fixture has never been shown to fail.
 */
class AdminRulesTest {

    private static ArchitectureRulesConfiguration bad() {
        return Fixtures.configurationFor("bad.admin").build();
    }

    @Test
    @DisplayName("a handler under /admin/ with neither annotation is reported, a placeholder path included")
    void undeclaredHandlerIsReported() {
        List<String> violations = Fixtures.violationDescriptions(bad(), AdminRules.HANDLER_DECLARES_AN_OPERATION);

        assertThat(violations)
                .anyMatch(description -> description.contains("UnannotatedAdminController.messages()")
                        && description.contains("/admin/v1/outbox/messages"))
                .anyMatch(description -> description.contains("UnannotatedAdminController.subjects()")
                        && description.contains("/admin/settings/subjects"))
                // A placeholder with no default is unknowable from bytecode: skipped, never guessed at.
                .noneMatch(description -> description.contains("unknowable"))
                .noneMatch(description -> description.contains("GoodAdminController"))
                .noneMatch(description -> description.contains("OrdinaryApiController"));
    }

    @Test
    @DisplayName("a second gate is reported on the method and on the class")
    void secondGateIsReported() {
        List<String> violations = Fixtures.violationDescriptions(bad(), AdminRules.NO_SECOND_GATE);

        assertThat(violations)
                .anyMatch(description -> description.contains("DoubleGatedAdminController.list()")
                        && description.contains("PreAuthorize"))
                .anyMatch(description -> description.contains("ClassGatedAdminController")
                        && description.contains("PreAuthorize"))
                .noneMatch(description -> description.contains("GoodAdminController"));
    }

    @Test
    @DisplayName("an annotated handler mapped outside /admin/v1/ is reported")
    void misplacedHandlerIsReported() {
        List<String> violations =
                Fixtures.violationDescriptions(bad(), AdminRules.MAPPED_UNDER_THE_VERSIONED_PREFIX);

        assertThat(violations)
                .anyMatch(description -> description.contains("MisplacedAdminController.list()")
                        && description.contains("/api/v1/locks"))
                .noneMatch(description -> description.contains("GoodAdminController"));
    }

    @Test
    @DisplayName("an administrative controller reading a repository is reported; one using a service is not")
    void repositoryAccessIsReported() {
        List<String> violations = Fixtures.violationDescriptions(bad(), AdminRules.CONTROLLER_IS_A_THIN_ADAPTER);

        assertThat(violations)
                .anyMatch(description -> description.contains("RepositoryBackedAdminController"))
                .noneMatch(description -> description.contains("GoodAdminController"))
                .noneMatch(description -> description.contains("ThingAdminService"));
    }

    @Test
    @DisplayName("@AdminObject on a request parameter is reported; on a path variable it is not")
    void adminObjectOutsideAPathVariableIsReported() {
        List<String> violations =
                Fixtures.violationDescriptions(bad(), AdminRules.ADMIN_OBJECT_IS_A_PATH_VARIABLE);

        assertThat(violations)
                .anyMatch(description -> description.contains("ObjectAsQueryAdminController.poke"))
                .noneMatch(description -> description.contains("GoodAdminController"));
    }

    @Test
    @DisplayName("a module with no handler under /admin/ breaks none of the five rules")
    void moduleWithoutAdministrativeHandlersIsSilent() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("good").build()))
                .doesNotContain(
                        AdminRules.HANDLER_DECLARES_AN_OPERATION.value(),
                        AdminRules.NO_SECOND_GATE.value(),
                        AdminRules.MAPPED_UNDER_THE_VERSIONED_PREFIX.value(),
                        AdminRules.CONTROLLER_IS_A_THIN_ADAPTER.value(),
                        AdminRules.ADMIN_OBJECT_IS_A_PATH_VARIABLE.value());
    }

    @Test
    @DisplayName("the REST path rule leaves administrative controllers to this group, and still judges the others")
    void restPathRuleLeavesAdministrativeControllersAlone() {
        List<String> violations = Fixtures.violationDescriptions(bad(),
                ru.ludwigandreas.archrules.rules.RestPathRules.CONTROLLERS_DECLARE_VERSIONED_BASE_PATH);

        assertThat(violations)
                .noneMatch(description -> description.contains("GoodAdminController"))
                .noneMatch(description -> description.contains("RepositoryBackedAdminController"))
                // Not administrative - it declares no admin annotation - so it is still the REST path rule's.
                .anyMatch(description -> description.contains("UnannotatedAdminController"));
    }

    @Test
    @DisplayName("the group is on by default")
    void groupIsEnabledByDefault() {
        assertThat(ArchitectureRules.suite(bad()).rules())
                .extracting(rule -> rule.id().value())
                .contains(AdminRules.HANDLER_DECLARES_AN_OPERATION.value());
    }
}
