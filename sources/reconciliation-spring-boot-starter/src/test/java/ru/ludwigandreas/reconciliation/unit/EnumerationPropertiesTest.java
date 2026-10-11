package ru.ludwigandreas.reconciliation.unit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import ru.ludwigandreas.reconciliation.config.ReconciliationProperties;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The {@code enumerations} and {@code enumeration-defaults} blocks bind as the README spells them. */
class EnumerationPropertiesTest {

    @Test
    void anEnumerationBlockBindsEveryDocumentedKey() {
        ReconciliationProperties properties = bind(Map.ofEntries(
                Map.entry("ludwig.reconciliation.enumerations.jira-issues.enabled", "false"),
                Map.entry("ludwig.reconciliation.enumerations.jira-issues.schedule.fixed-delay", "2m"),
                Map.entry("ludwig.reconciliation.enumerations.jira-issues.schedule.run-timeout", "20m"),
                Map.entry("ludwig.reconciliation.enumerations.jira-issues.discovery.fixed-delay", "10m"),
                Map.entry("ludwig.reconciliation.enumerations.jira-issues.discovery.page-size", "250"),
                Map.entry("ludwig.reconciliation.enumerations.jira-issues.refresh.full-interval", "6h"),
                Map.entry("ludwig.reconciliation.enumerations.jira-issues.refresh.incremental-interval", "5m"),
                Map.entry("ludwig.reconciliation.enumerations.jira-issues.walk.page-size", "50"),
                Map.entry("ludwig.reconciliation.enumerations.jira-issues.walk.max-concurrency", "3"),
                Map.entry("ludwig.reconciliation.enumerations.jira-issues.walk.max-scopes-per-run", "40"),
                Map.entry("ludwig.reconciliation.enumerations.jira-issues.sweep.confirm-after-misses", "2"),
                Map.entry("ludwig.reconciliation.enumerations.jira-issues.sweep.max-fraction", "0.25"),
                Map.entry("ludwig.reconciliation.enumerations.jira-issues.retry.max-attempts", "5"),
                Map.entry("ludwig.reconciliation.enumerations.jira-issues.rate-limit", "jira"),
                Map.entry("ludwig.reconciliation.enumerations.jira-issues.rest-client", "jira")));

        ReconciliationProperties.Enumeration enumeration = properties.getEnumerations().get("jira-issues");

        assertThat(enumeration.isEnabled()).isFalse();
        assertThat(enumeration.getSchedule().getFixedDelay()).isEqualTo(Duration.ofMinutes(2));
        assertThat(enumeration.getSchedule().getRunTimeout()).isEqualTo(Duration.ofMinutes(20));
        assertThat(enumeration.getDiscovery().getFixedDelay()).isEqualTo(Duration.ofMinutes(10));
        assertThat(enumeration.getDiscovery().getPageSize()).isEqualTo(250);
        assertThat(enumeration.getRefresh().getFullInterval()).isEqualTo(Duration.ofHours(6));
        assertThat(enumeration.getRefresh().getIncrementalInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(enumeration.getWalk().getPageSize()).isEqualTo(50);
        assertThat(enumeration.getWalk().getMaxConcurrency()).isEqualTo(3);
        assertThat(enumeration.getWalk().getMaxScopesPerRun()).isEqualTo(40);
        assertThat(enumeration.getSweep().getConfirmAfterMisses()).isEqualTo(2);
        assertThat(enumeration.getSweep().getMaxFraction()).isEqualTo(0.25);
        assertThat(enumeration.getRetry().getMaxAttempts()).isEqualTo(5);
        assertThat(enumeration.getRateLimit()).isEqualTo("jira");
        assertThat(enumeration.getRestClient()).isEqualTo("jira");
    }

    @Test
    void theDefaultsBlockBindsBesideTheEnumerations() {
        ReconciliationProperties properties = bind(Map.of(
                "ludwig.reconciliation.enumeration-defaults.walk.page-size", "75",
                "ludwig.reconciliation.enumeration-defaults.sweep.max-fraction", "0.1"));

        assertThat(properties.getEnumerationDefaults().getWalk().getPageSize()).isEqualTo(75);
        assertThat(properties.getEnumerationDefaults().getSweep().getMaxFraction()).isEqualTo(0.1);
        assertThat(properties.getEnumerations()).isEmpty();
    }

    private static ReconciliationProperties bind(Map<String, String> source) {
        return new Binder(new MapConfigurationPropertySource(source))
                .bind("ludwig.reconciliation", ReconciliationProperties.class)
                .orElseGet(ReconciliationProperties::new);
    }
}
