package ru.ludwigandreas.archrules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The toggle semantics: specificity wins, and a group switch never activates an opt-in rule. */
class RuleSelectionTest {

    private static final ArchitectureRule ENABLED_BY_DEFAULT =
            ArchitectureRule.of(RuleId.of(RuleGroup.WEB, "some-rule"), new NoOpArchRule());
    private static final ArchitectureRule OPT_IN =
            ArchitectureRule.optIn(RuleId.of(RuleGroup.WEB, "strict-rule"), new NoOpArchRule());
    private static final ArchitectureRule IN_OPT_IN_GROUP =
            ArchitectureRule.of(RuleId.of(RuleGroup.DOMAIN_ISOLATION, "some-rule"), new NoOpArchRule());
    private static final ArchitectureRule QUALIFIED =
            ArchitectureRule.of(RuleId.of(RuleGroup.CYCLES, "per-module", "com.acme.orders"), new NoOpArchRule());

    @Test
    void defaultsFollowTheGroupAndTheRule() {
        RuleSelection selection = RuleSelection.none();

        assertThat(selection.isEnabled(ENABLED_BY_DEFAULT)).isTrue();
        assertThat(selection.isEnabled(OPT_IN)).isFalse();
        assertThat(selection.isEnabled(IN_OPT_IN_GROUP)).isFalse();
    }

    @Test
    void theMoreSpecificSelectorWins() {
        RuleSelection selection = RuleSelection.disabling("web").enable("web.some-rule");

        assertThat(selection.isEnabled(ENABLED_BY_DEFAULT)).isTrue();
    }

    @Test
    void enablingAGroupDoesNotActivateItsOptInRules() {
        RuleSelection selection = RuleSelection.enabling("web");

        assertThat(selection.isEnabled(OPT_IN)).isFalse();
        assertThat(selection.enable("web.strict-rule").isEnabled(OPT_IN)).isTrue();
    }

    @Test
    void enablingAnOptInGroupActivatesItsOrdinaryRules() {
        assertThat(RuleSelection.enabling("domain-isolation").isEnabled(IN_OPT_IN_GROUP)).isTrue();
    }

    @Test
    void theWildcardIsTheWeakestSelector() {
        RuleSelection selection = RuleSelection.enabling("*").disable("web");

        assertThat(selection.isEnabled(ENABLED_BY_DEFAULT)).isFalse();
        assertThat(selection.isEnabled(IN_OPT_IN_GROUP)).isTrue();
    }

    @Test
    void aQualifiedRuleIsAddressableAsAWholeOrOneInstanceAtATime() {
        assertThat(RuleSelection.disabling("cycles.per-module").isEnabled(QUALIFIED)).isFalse();
        assertThat(RuleSelection.disabling("cycles.per-module[com.acme.orders]").isEnabled(QUALIFIED)).isFalse();
        assertThat(RuleSelection.disabling("cycles.per-module[com.acme.billing]").isEnabled(QUALIFIED)).isTrue();
    }

    @Test
    void laterSettingsReplaceEarlierOnes() {
        assertThat(RuleSelection.enabling("web").disable("web").isEnabled(ENABLED_BY_DEFAULT)).isFalse();
        assertThat(RuleSelection.disabling("web").mergedWith(RuleSelection.enabling("web"))
                .isEnabled(ENABLED_BY_DEFAULT)).isTrue();
    }

    @Test
    void aTypoInASelectorFailsImmediately() {
        assertThatThrownBy(() -> RuleSelection.disabling("kafak"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown rule selector");
    }

}
