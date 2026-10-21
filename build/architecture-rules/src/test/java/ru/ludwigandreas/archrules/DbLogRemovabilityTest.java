package ru.ludwigandreas.archrules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.archrules.rules.KafkaRules;

class DbLogRemovabilityTest {

    @Test
    @DisplayName("a class that names the DB-log transport is reported")
    void aReferenceToTheDbLogIsReported() {
        assertThat(Fixtures.violationDescriptions(
                Fixtures.configurationFor("bad.dblog").build(), KafkaRules.NOTHING_DEPENDS_ON_THE_DB_LOG))
                .anyMatch(description -> description.contains("LagReportingService"));
    }

    @Test
    @DisplayName("a module that does not name it does not break the rule")
    void compliantModuleIsSilent() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("good").build()))
                .doesNotContain(KafkaRules.NOTHING_DEPENDS_ON_THE_DB_LOG.value());
    }
}
