package ru.ludwigandreas.archrules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.archrules.rules.KafkaRules;

class ProducerSeamRuleTest {

    @Test
    @DisplayName("a class constructing its own DefaultKafkaProducerFactory is reported")
    void anOwnProducerFactoryIsReported() {
        assertThat(Fixtures.violationDescriptions(
                Fixtures.configurationFor("bad.producers").build(), KafkaRules.PRODUCERS_COME_FROM_THE_CLUSTER_SEAM))
                .anyMatch(description -> description.contains("OwnProducerConfig"));
    }

    @Test
    @DisplayName("a module that builds no producer does not break the rule")
    void compliantModuleIsSilent() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("good").build()))
                .doesNotContain(KafkaRules.PRODUCERS_COME_FROM_THE_CLUSTER_SEAM.value());
    }
}
