package ru.ludwigandreas.observability.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.ludwigandreas.audit.CorrelationProvider;
import ru.ludwigandreas.audit.config.AuditCoreAutoConfiguration;
import ru.ludwigandreas.observability.config.ObservabilityCoreAutoConfiguration;
import ru.ludwigandreas.observability.correlation.CorrelationContext;

/** audit-core's correlation seam is served by this module, ahead of audit-core's empty fallback. */
class ObservabilityCorrelationProviderTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AuditCoreAutoConfiguration.class,
                    ObservabilityCoreAutoConfiguration.class));

    @Test
    void theProviderReadsTheCurrentCorrelationId() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(CorrelationProvider.class);
            CorrelationProvider provider = context.getBean(CorrelationProvider.class);
            CorrelationContext correlation = context.getBean(CorrelationContext.class);

            assertThat(provider.currentCorrelationId()).isEmpty();
            try (CorrelationContext.Scope ignored = correlation.open("c-9")) {
                assertThat(provider.currentCorrelationId()).contains("c-9");
            }
        });
    }

    @Test
    void aServicesOwnProviderStillWins() {
        CorrelationProvider own = () -> Optional.of("own");
        runner.withBean("ownCorrelationProvider", CorrelationProvider.class, () -> own)
                .run(context -> assertThat(context.getBean(CorrelationProvider.class)).isSameAs(own));
    }
}
