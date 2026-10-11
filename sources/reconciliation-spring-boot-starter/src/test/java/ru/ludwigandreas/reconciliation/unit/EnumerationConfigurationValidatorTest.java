package ru.ludwigandreas.reconciliation.unit;

import org.junit.jupiter.api.Test;
import ru.ludwigandreas.reconciliation.api.EnumerationTask;
import ru.ludwigandreas.reconciliation.config.EnumerationConfigurationValidator;
import ru.ludwigandreas.reconciliation.config.EnumerationSettingsResolver;
import ru.ludwigandreas.reconciliation.config.ReconciliationProperties;
import ru.ludwigandreas.reconciliation.exception.ReconciliationConfigurationException;

import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** A configuration that is individually valid and jointly wrong does not start. */
class EnumerationConfigurationValidatorTest {

    private static final String NAME = StubEnumeration.ANNOTATED_NAME;

    @Test
    void aValidConfigurationStarts() {
        assertThatCode(() -> validate(configured(), List.of(new StubEnumeration(NAME, false))))
                .doesNotThrowAnyException();
    }

    @Test
    void aServiceWithNoEnumerationAtAllStarts() {
        assertThatCode(() -> validate(new ReconciliationProperties(), List.of())).doesNotThrowAnyException();
    }

    @Test
    void aConfiguredEnumerationWithNoBeanIsRefused() {
        assertThatExceptionOfType(ReconciliationConfigurationException.class)
                .isThrownBy(() -> validate(configured(), List.of()))
                .withMessageContaining("Enumeration \"items\" is configured but no @ReconciliationEnumeration bean");
    }

    @Test
    void aBeanWithNoConfigurationIsRefused() {
        assertThatExceptionOfType(ReconciliationConfigurationException.class)
                .isThrownBy(() -> validate(new ReconciliationProperties(), List.of(new StubEnumeration(NAME, false))))
                .withMessageContaining("implements enumeration \"items\", which has no block under "
                        + "ludwig.reconciliation.enumerations");
    }

    @Test
    void aBeanWhoseAnnotationAndNameDisagreeIsRefused() {
        assertThatExceptionOfType(ReconciliationConfigurationException.class)
                .isThrownBy(() -> validate(configured(), List.of(new StubEnumeration("something-else", false))))
                .withMessageContaining("is annotated @ReconciliationEnumeration(\"items\")");
    }

    @Test
    void aNameSharedWithAKeyAddressedTaskIsRefused() {
        ReconciliationProperties properties = configured();
        properties.getTasks().put(NAME, new ReconciliationProperties.Task());

        assertThatExceptionOfType(ReconciliationConfigurationException.class)
                .isThrownBy(() -> validate(properties, List.of(new StubEnumeration(NAME, false))))
                .withMessageContaining("\"items\" is configured both under ludwig.reconciliation.tasks");
    }

    @Test
    void anUnknownRateLimitIsRefused() {
        ReconciliationProperties properties = configured();
        properties.getEnumerations().get(NAME).setRateLimit("jira");

        assertThatExceptionOfType(ReconciliationConfigurationException.class)
                .isThrownBy(() -> validate(properties, List.of(new StubEnumeration(NAME, false))))
                .withMessageContaining("references rate limit \"jira\", which is not configured");
    }

    @Test
    void anUnknownRestClientIsRefused() {
        ReconciliationProperties properties = configured();
        properties.getEnumerations().get(NAME).setRestClient("jira");

        assertThatExceptionOfType(ReconciliationConfigurationException.class)
                .isThrownBy(() -> validate(properties, List.of(new StubEnumeration(NAME, false)), name -> false))
                .withMessageContaining("names REST client \"jira\", which is not configured");
    }

    @Test
    void anIncrementalIntervalOverAFeedWithNoChangedSinceIsRefused() {
        ReconciliationProperties properties = configured();
        properties.getEnumerations().get(NAME).setRefresh(new ReconciliationProperties.Refresh());
        properties.getEnumerations().get(NAME).getRefresh().setIncrementalInterval(Duration.ofMinutes(5));

        assertThatExceptionOfType(ReconciliationConfigurationException.class)
                .isThrownBy(() -> validate(properties, List.of(new StubEnumeration(NAME, false))))
                .withMessageContaining("Enumeration \"items\" sets refresh.incremental-interval")
                .withMessageContaining("does not declare supportsChangedSince()");
        assertThatCode(() -> validate(properties, List.of(new StubEnumeration(NAME, true))))
                .doesNotThrowAnyException();
    }

    @Test
    void aSweepFractionOutsideItsRangeIsRefused() {
        for (double fraction : new double[] {0.0, -0.5, 1.5}) {
            ReconciliationProperties properties = configured();
            properties.getEnumerations().get(NAME).setSweep(new ReconciliationProperties.Sweep());
            properties.getEnumerations().get(NAME).getSweep().setMaxFraction(fraction);

            assertThatExceptionOfType(ReconciliationConfigurationException.class)
                    .isThrownBy(() -> validate(properties, List.of(new StubEnumeration(NAME, false))))
                    .withMessageContaining("sweep.max-fraction");
        }
    }

    @Test
    void aConsecutiveMissCountBelowOneIsRefused() {
        ReconciliationProperties properties = configured();
        properties.getEnumerations().get(NAME).setSweep(new ReconciliationProperties.Sweep());
        properties.getEnumerations().get(NAME).getSweep().setConfirmAfterMisses(0);

        assertThatExceptionOfType(ReconciliationConfigurationException.class)
                .isThrownBy(() -> validate(properties, List.of(new StubEnumeration(NAME, false))))
                .withMessageContaining("sweep.confirm-after-misses 0");
    }

    @Test
    void everyProblemIsReportedInOnePass() {
        ReconciliationProperties properties = configured();
        properties.getTasks().put(NAME, new ReconciliationProperties.Task());
        properties.getEnumerations().get(NAME).setRateLimit("jira");
        properties.getEnumerations().get(NAME).setSweep(new ReconciliationProperties.Sweep());
        properties.getEnumerations().get(NAME).getSweep().setConfirmAfterMisses(0);

        assertThatExceptionOfType(ReconciliationConfigurationException.class)
                .isThrownBy(() -> validate(properties, List.of(new StubEnumeration(NAME, false))))
                .withMessageContaining("configured both under")
                .withMessageContaining("references rate limit")
                .withMessageContaining("sweep.confirm-after-misses");
    }

    private static ReconciliationProperties configured() {
        ReconciliationProperties properties = new ReconciliationProperties();
        properties.getEnumerations().put(NAME, new ReconciliationProperties.Enumeration());
        return properties;
    }

    private static void validate(ReconciliationProperties properties, List<EnumerationTask<?, ?, ?, ?>> beans) {
        validate(properties, beans, name -> true);
    }

    private static void validate(ReconciliationProperties properties,
                                 List<EnumerationTask<?, ?, ?, ?>> beans,
                                 Predicate<String> restClientExists) {
        new EnumerationConfigurationValidator(properties, EnumerationSettingsResolver.resolveAll(properties),
                beans, restClientExists).validate();
    }
}
