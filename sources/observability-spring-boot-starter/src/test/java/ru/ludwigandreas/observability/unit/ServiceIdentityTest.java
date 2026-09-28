package ru.ludwigandreas.observability.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ru.ludwigandreas.observability.core.ServiceIdentity;

/** One identity, exported two ways, which is what keeps a dashboard and a trace search in agreement. */
class ServiceIdentityTest {

    @Test
    void exportsOpenTelemetryResourceAttributeNames() {
        ServiceIdentity identity = new ServiceIdentity("catalog", "commerce", "1.4.2", "prod", "catalog-7d9f");

        assertThat(identity.toResourceAttributes()).containsOnly(
                org.assertj.core.api.Assertions.entry("service.name", "catalog"),
                org.assertj.core.api.Assertions.entry("service.namespace", "commerce"),
                org.assertj.core.api.Assertions.entry("service.version", "1.4.2"),
                org.assertj.core.api.Assertions.entry("service.instance.id", "catalog-7d9f"),
                org.assertj.core.api.Assertions.entry("deployment.environment", "prod"));
    }

    @Test
    void exportsPrometheusSafeMetricTagNames() {
        ServiceIdentity identity = new ServiceIdentity("catalog", "commerce", "1.4.2", "prod", "catalog-7d9f");

        // Dotted keys are not legal Prometheus labels and would be rewritten under the developer's
        // feet, so the metric tags are named the way they will actually be scraped.
        assertThat(identity.toCommonMetricTags()).containsOnlyKeys("service", "namespace", "version", "environment");
    }

    @Test
    void leavesInstanceOutOfMetricTags() {
        ServiceIdentity identity = new ServiceIdentity("catalog", null, null, null, "catalog-7d9f");

        // Prometheus already attaches an instance label from the scrape target. A second one collides
        // with it and multiplies every series by the replica count - the classic way a metrics bill
        // doubles after a "harmless" tagging change.
        assertThat(identity.toCommonMetricTags()).doesNotContainKey("instance");
    }

    @Test
    void omitsUnsetFieldsRatherThanExportingThemAsUnknown() {
        ServiceIdentity identity = new ServiceIdentity("catalog", null, "  ", "", null);

        // A literal "unknown" looks like a real value in a backend's facet list and silently becomes
        // a group several unrelated services share.
        assertThat(identity.toResourceAttributes()).containsOnlyKeys("service.name");
        assertThat(identity.toCommonMetricTags()).containsOnlyKeys("service");
    }

    @Test
    void normalisesBlankValuesToNull() {
        ServiceIdentity identity = new ServiceIdentity("  ", "", null, "\t", "x");

        assertThat(identity.name()).isNull();
        assertThat(identity.namespace()).isNull();
        assertThat(identity.environment()).isNull();
        assertThat(identity.instance()).isEqualTo("x");
    }
}
