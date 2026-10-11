package ru.ludwigandreas.reconciliation.unit;

import org.junit.jupiter.api.Test;
import ru.ludwigandreas.reconciliation.config.EnumerationSettings;
import ru.ludwigandreas.reconciliation.config.EnumerationSettingsResolver;
import ru.ludwigandreas.reconciliation.config.ReconciliationProperties;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Task, then defaults, then the built-in value - per field, not per block. */
class EnumerationSettingsResolverTest {

    @Test
    void anEnumerationThatSaysNothingGetsTheBuiltInValues() {
        EnumerationSettings settings = resolve(new ReconciliationProperties.Enumeration(),
                new ReconciliationProperties.EnumerationDefaults());

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.schedule().fixedDelay()).isEqualTo(Duration.ofMinutes(1));
        assertThat(settings.runTimeout()).isEqualTo(Duration.ofMinutes(15));
        assertThat(settings.discoverySchedule().fixedDelay()).isEqualTo(Duration.ofMinutes(5));
        assertThat(settings.fullInterval()).isEqualTo(Duration.ofHours(6));
        assertThat(settings.isIncremental()).isFalse();
        assertThat(settings.pageSize()).isEqualTo(100);
        assertThat(settings.maxConcurrency()).isEqualTo(2);
        assertThat(settings.maxScopesPerRun()).isEqualTo(50);
        assertThat(settings.sweepConfirmAfterMisses()).isEqualTo(1);
        assertThat(settings.sweepMaxFraction()).isEqualTo(0.5);
        assertThat(settings.retry().maxAttempts()).isEqualTo(8);
        assertThat(settings.auditEnabled()).isTrue();
    }

    @Test
    void anEnumerationThatSaysNothingInheritsTheDefaultsBlock() {
        ReconciliationProperties.EnumerationDefaults defaults = new ReconciliationProperties.EnumerationDefaults();
        defaults.setWalk(new ReconciliationProperties.Walk());
        defaults.getWalk().setPageSize(25);
        defaults.setRefresh(new ReconciliationProperties.Refresh());
        defaults.getRefresh().setIncrementalInterval(Duration.ofMinutes(3));

        EnumerationSettings settings = resolve(new ReconciliationProperties.Enumeration(), defaults);

        assertThat(settings.pageSize()).isEqualTo(25);
        assertThat(settings.incrementalInterval()).isEqualTo(Duration.ofMinutes(3));
    }

    @Test
    void overridingOneFieldOfABlockKeepsTheDefaultsForItsOtherFields() {
        ReconciliationProperties.EnumerationDefaults defaults = new ReconciliationProperties.EnumerationDefaults();
        defaults.setWalk(new ReconciliationProperties.Walk());
        defaults.getWalk().setPageSize(25);
        defaults.getWalk().setMaxScopesPerRun(7);
        ReconciliationProperties.Enumeration enumeration = new ReconciliationProperties.Enumeration();
        enumeration.setWalk(new ReconciliationProperties.Walk());
        enumeration.getWalk().setMaxConcurrency(9);

        EnumerationSettings settings = resolve(enumeration, defaults);

        assertThat(settings.maxConcurrency()).isEqualTo(9);
        assertThat(settings.pageSize()).isEqualTo(25);
        assertThat(settings.maxScopesPerRun()).isEqualTo(7);
    }

    @Test
    void anEnumerationThatSetsACronDoesNotInheritADefaultFixedDelay() {
        ReconciliationProperties.EnumerationDefaults defaults = new ReconciliationProperties.EnumerationDefaults();
        defaults.setSchedule(new ReconciliationProperties.Schedule());
        defaults.getSchedule().setFixedDelay(Duration.ofSeconds(30));
        defaults.getSchedule().setRunTimeout(Duration.ofMinutes(7));
        ReconciliationProperties.Enumeration enumeration = new ReconciliationProperties.Enumeration();
        enumeration.setSchedule(new ReconciliationProperties.Schedule());
        enumeration.getSchedule().setCron("0 0 * * * *");

        EnumerationSettings settings = resolve(enumeration, defaults);

        assertThat(settings.schedule().isCron()).isTrue();
        assertThat(settings.schedule().cron()).isEqualTo("0 0 * * * *");
        // The run timeout is not part of the cron/fixed-delay pair, so it still comes from defaults.
        assertThat(settings.runTimeout()).isEqualTo(Duration.ofMinutes(7));
    }

    @Test
    void everyConfiguredEnumerationIsResolvedUnderItsOwnName() {
        ReconciliationProperties properties = new ReconciliationProperties();
        properties.getEnumerations().put("first", new ReconciliationProperties.Enumeration());
        properties.getEnumerations().put("second", new ReconciliationProperties.Enumeration());

        assertThat(EnumerationSettingsResolver.resolveAll(properties).keySet()).containsExactly("first", "second");
        assertThat(EnumerationSettingsResolver.resolveAll(properties).get("second").name()).isEqualTo("second");
    }

    private static EnumerationSettings resolve(ReconciliationProperties.Enumeration enumeration,
                                               ReconciliationProperties.EnumerationDefaults defaults) {
        return EnumerationSettingsResolver.resolve("items", enumeration, defaults);
    }
}
