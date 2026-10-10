package ru.ludwigandreas.restclient.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.observability.config.ObservabilityCoreAutoConfiguration;
import ru.ludwigandreas.restclient.config.PlatformCallContextSource;
import ru.ludwigandreas.restclient.config.RestClientObservabilityAutoConfiguration;
import ru.ludwigandreas.restclient.core.CallContextSource;

/**
 * Exactly one {@link CallContextSource} exists, however this starter is combined with observability.
 *
 * <p>This test exists because the combination once did not start at all. The no-identity fallback
 * and the platform source were both registered, the client builder takes one, and the context failed
 * with "expected single matching bean but found 2" - in the first service that put the two starters
 * together, because nothing here had ever booted both. The other integration tests still run
 * without observability, on purpose; this is the one that runs with it.
 *
 * <p>Asserted on the assembled context rather than by a structural rule: which of two conditional
 * beans survives is decided by the order auto-configurations are evaluated in, and that is visible
 * nowhere but in a context that has been started.
 */
class CallContextSourceWiringTest {

    private final ApplicationContextRunner withoutObservability = RestClientTestSupport.runner()
            .withConfiguration(AutoConfigurations.of(RestClientObservabilityAutoConfiguration.class));

    private final ApplicationContextRunner withObservability = withoutObservability
            .withConfiguration(AutoConfigurations.of(ObservabilityCoreAutoConfiguration.class));

    @Test
    @DisplayName("with observability present the platform source is the only one")
    void thePlatformSourceReplacesTheFallback() {
        withObservability.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(CallContextSource.class);
            assertThat(context.getBean(CallContextSource.class)).isInstanceOf(PlatformCallContextSource.class);
        });
    }

    @Test
    @DisplayName("without observability the fallback is the only one")
    void theFallbackStandsAlone() {
        withoutObservability.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(CallContextSource.class);
            assertThat(context.getBean(CallContextSource.class)).isSameAs(CallContextSource.NONE);
        });
    }

    @Test
    @DisplayName("with observability switched off the fallback is the only one")
    void switchingObservabilityOffLeavesTheFallback() {
        withObservability.withPropertyValues("ludwig.observability.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(CallContextSource.class);
            assertThat(context.getBean(CallContextSource.class)).isSameAs(CallContextSource.NONE);
        });
    }

    @Test
    @DisplayName("a source the application declares is the only one, with or without observability")
    void anApplicationSourceReplacesBoth() {
        withObservability.withUserConfiguration(OwnSourceConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(CallContextSource.class);
            assertThat(context.getBean(CallContextSource.class)).isSameAs(OwnSourceConfiguration.OWN);
        });
        withoutObservability.withUserConfiguration(OwnSourceConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(CallContextSource.class);
            assertThat(context.getBean(CallContextSource.class)).isSameAs(OwnSourceConfiguration.OWN);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnSourceConfiguration {

        static final CallContextSource OWN = new CallContextSource() {
        };

        @Bean
        CallContextSource applicationCallContextSource() {
            return OWN;
        }
    }
}
