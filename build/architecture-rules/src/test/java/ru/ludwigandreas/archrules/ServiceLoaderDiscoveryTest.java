package ru.ludwigandreas.archrules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Rule sets published by a jar on the test classpath are picked up without any test code change. */
class ServiceLoaderDiscoveryTest {

    @Test
    @DisplayName("a rule set published through META-INF/services is part of the suite")
    void publishedRuleSetsAreDiscovered() {
        assertThat(Fixtures.resolvedRuleIds(Fixtures.configurationFor("good").build()))
                .contains(ServiceLoaderRuleSet.RULE_ID.value());
    }

    @Test
    @DisplayName("discovery can be switched off")
    void discoveryCanBeDisabled() {
        assertThat(Fixtures.resolvedRuleIds(Fixtures.configurationFor("good")
                .includeServiceLoaderRuleSets(false)
                .build()))
                .doesNotContain(ServiceLoaderRuleSet.RULE_ID.value());
    }
}
