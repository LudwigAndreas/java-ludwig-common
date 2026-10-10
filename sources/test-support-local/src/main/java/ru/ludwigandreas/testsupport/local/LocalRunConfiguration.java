package ru.ludwigandreas.testsupport.local;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import ru.ludwigandreas.restclient.core.RestClientRegistry;
import ru.ludwigandreas.testsupport.local.fixture.SeedFixtureConfiguration;
import ru.ludwigandreas.testsupport.local.fixture.SeedFixtures;
import ru.ludwigandreas.testsupport.local.stub.StubServers;

/**
 * What a local run adds to the service's own context.
 *
 * <p>Added by {@link LocalRun} as a source of the service's {@code SpringApplication}, and by nothing
 * else: this module registers no auto-configuration, so being on a test classpath adds none of this
 * to an ordinary test context.
 */
@Configuration(proxyBeanMethods = false)
@Import(SeedFixtureConfiguration.class)
public class LocalRunConfiguration {

    @Bean
    LocalFixtureRunner ludwigLocalFixtureRunner(LocalRunPlan plan, SeedFixtures fixtures,
                                                Environment environment) {
        return new LocalFixtureRunner(plan, fixtures, environment);
    }

    @Bean
    LocalRunSummary ludwigLocalRunSummary(LocalRunPlan plan, ObjectProvider<StubServers> stubs,
                                          ObjectProvider<SummaryContributor> contributors,
                                          LocalFixtureRunner fixtures, Environment environment) {
        return new LocalRunSummary(plan, stubs, contributors, fixtures, environment);
    }

    @Bean
    StubAddressCheck ludwigLocalStubAddressCheck(ObjectProvider<StubServers> stubs,
                                                 ConfigurableEnvironment environment) {
        StubServers started = stubs.getIfAvailable();
        return new StubAddressCheck(started == null ? Map.of() : started.declared(), environment);
    }

    /**
     * The client-implies-stub check, present only where there are rest clients to check.
     *
     * <p>Kept in a nested class behind a class condition so that a service without the rest-client
     * starter never has {@code RestClientRegistry} resolved: the outer class does not name it.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "ru.ludwigandreas.restclient.core.RestClientRegistry")
    static class StubCoverageConfiguration {

        // No @ConditionalOnBean: this class is a primary source, processed before the starter's
        // auto-configuration has registered the registry, so the condition would always be false
        // and the check would silently never exist. The registry is looked up when the bean is made.
        @Bean
        StubCoverageCheck ludwigLocalStubCoverageCheck(ObjectProvider<RestClientRegistry> registry,
                                                       ObjectProvider<StubServers> stubs,
                                                       Environment environment) {
            RestClientRegistry clients = registry.getIfAvailable();
            StubServers started = stubs.getIfAvailable();
            // With the starter on the classpath but switched off there is no registry and no
            // client to be left without a stub.
            Set<String> names = clients == null ? Set.of() : clients.names();
            Set<String> stubbed = started == null ? Set.of() : started.declared().entrySet().stream()
                    .filter(entry -> entry.getValue().address().isRestClient())
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
            Set<String> declaredPassThrough = started == null ? Set.of() : started.passThrough();
            return new StubCoverageCheck(names, stubbed,
                    client -> declaredPassThrough.contains(client)
                            || StubServers.PASS_THROUGH_MODE.equalsIgnoreCase(environment.getProperty(
                            StubServers.PROPERTY_PREFIX + client + ".mode")));
        }
    }
}
