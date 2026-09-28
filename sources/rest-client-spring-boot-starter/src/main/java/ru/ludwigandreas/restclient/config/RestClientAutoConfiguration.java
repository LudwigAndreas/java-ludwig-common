package ru.ludwigandreas.restclient.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.OrderComparator;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;
import ru.ludwigandreas.restclient.auth.ClientAuthenticatorFactory;
import ru.ludwigandreas.restclient.core.CallContextSource;
import ru.ludwigandreas.restclient.core.ClientRuntime;
import ru.ludwigandreas.restclient.core.DefaultRestClientRegistry;
import ru.ludwigandreas.restclient.core.NamedClientFactory;
import ru.ludwigandreas.restclient.core.RestClientRegistry;
import ru.ludwigandreas.restclient.observability.RestClientMeters;
import ru.ludwigandreas.restclient.resilience.ResilienceMetricsBinder;
import ru.ludwigandreas.restclient.resilience.ResiliencePolicyFactory;
import ru.ludwigandreas.restclient.resilience.ResilienceRegistries;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.audit.redaction.SensitivityClassifier;
import ru.ludwigandreas.restclient.spi.LudwigRestClientCustomizer;
import ru.ludwigandreas.restclient.spi.LudwigWebClientCustomizer;
import ru.ludwigandreas.restclient.spi.ResponseErrorTranslator;
import ru.ludwigandreas.restclient.spi.RestClientListener;

/**
 * The core of the starter: properties, registries, the client registry, and the startup validator.
 *
 * <p>Everything it declares is {@code @ConditionalOnMissingBean}, so every default here is an
 * opinion a service can replace by publishing its own bean. That is the rule this module is built
 * on - extension is publishing a bean, never editing this class.
 *
 * <p>The whole configuration is conditional on {@code ludwig.rest-client.enabled}, which defaults to
 * true. Setting it to false registers nothing: no clients, no proxies, no meters, no validator. A
 * service keeps the dependency and the feature is simply absent, which is what a test slice or a
 * profile that must not reach the network needs.
 */
@AutoConfiguration(after = JacksonAutoConfiguration.class)
@ConditionalOnClass(RestClient.class)
@ConditionalOnProperty(prefix = RestClientProperties.PREFIX, name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RestClientProperties.class)
public class RestClientAutoConfiguration {

    /**
     * Registers the named {@code RestClient}/{@code WebClient} beans.
     *
     * <p>Static, because a {@code BeanDefinitionRegistryPostProcessor} must be instantiated before
     * the configuration class that declares it. A non-static factory method forces this class - and
     * everything it injects - into existence far too early, which shows up as "is not eligible for
     * getting processed by all BeanPostProcessors" warnings and, eventually, as an
     * auto-configuration that silently did not apply.
     */
    @Bean
    @ConditionalOnMissingBean
    public static NamedClientBeanRegistrar ludwigNamedClientBeanRegistrar() {
        return new NamedClientBeanRegistrar();
    }

    /**
     * The clock every timing decision in this module measures against.
     *
     * <p>A bean rather than {@code Clock.systemUTC()} inline, because token expiry, {@code
     * Retry-After} and the retry budget are all clock-dependent, and a test that cannot move the
     * clock has to test them with {@code Thread.sleep}.
     */
    @Bean
    @ConditionalOnMissingBean(name = "ludwigRestClientClock")
    public Clock ludwigRestClientClock() {
        return Clock.systemUTC();
    }

    /**
     * The Resilience4j registries, reusing the application's own where it has them.
     *
     * <p>Reuse matters: a service that already uses Resilience4j for something else must end up with
     * one set of breakers and one set of metrics, not two that disagree about the same dependency.
     */
    @Bean
    @ConditionalOnMissingBean
    public ResilienceRegistries ludwigResilienceRegistries(
            ObjectProvider<CircuitBreakerRegistry> circuitBreakers,
            ObjectProvider<BulkheadRegistry> bulkheads,
            ObjectProvider<ThreadPoolBulkheadRegistry> threadPoolBulkheads,
            ObjectProvider<RateLimiterRegistry> rateLimiters,
            ObjectProvider<TimeLimiterRegistry> timeLimiters) {
        return new ResilienceRegistries(
                circuitBreakers.getIfAvailable(CircuitBreakerRegistry::ofDefaults),
                bulkheads.getIfAvailable(BulkheadRegistry::ofDefaults),
                threadPoolBulkheads.getIfAvailable(ThreadPoolBulkheadRegistry::ofDefaults),
                rateLimiters.getIfAvailable(RateLimiterRegistry::ofDefaults),
                timeLimiters.getIfAvailable(TimeLimiterRegistry::ofDefaults));
    }

    /**
     * Makes the Resilience4j registries visible to the monitoring stack.
     *
     * <p>A breaker that opens with no meter behind it is a state change nobody can alert on, which is
     * the difference between having a circuit breaker and knowing that you have one.
     */
    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnMissingBean
    public ResilienceMetricsBinder ludwigResilienceMetricsBinder(ResilienceRegistries registries,
                                                                 MeterRegistry meterRegistry) {
        return new ResilienceMetricsBinder(registries, meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public ResiliencePolicyFactory ludwigResiliencePolicyFactory(ResilienceRegistries registries,
                                                                 Clock ludwigRestClientClock,
                                                                 ResourceLoader resourceLoader) {
        return new ResiliencePolicyFactory(registries, ludwigRestClientClock,
                resourceLoader.getClassLoader());
    }

    @Bean
    @ConditionalOnMissingBean
    public RestClientMeters ludwigRestClientMeters(ObjectProvider<MeterRegistry> meterRegistry) {
        // A simple registry when the application has none: the counters then exist and go nowhere,
        // which keeps every call site free of null checks on a path that runs per request.
        return new RestClientMeters(meterRegistry.getIfAvailable(
                io.micrometer.core.instrument.simple.SimpleMeterRegistry::new));
    }

    /** With neither observability nor security present, calls carry no ambient identity. */
    @Bean
    @ConditionalOnMissingBean
    public CallContextSource ludwigRestClientCallContextSource() {
        return CallContextSource.NONE;
    }

    // CHECKSTYLE.OFF: ParameterNumber - a @Bean method's parameters are its injection points, and a
    // runtime builder that assembles every layer of a client genuinely has one per layer. Wrapping
    // them in a holder object would move the list into a second file without removing anything.
    @Bean
    @ConditionalOnMissingBean
    public ClientRuntimeBuilder ludwigClientRuntimeBuilder(
            RestClientProperties properties, ClientAuthenticatorFactory authenticators,
            ResiliencePolicyFactory policies, ObjectProvider<RestClientListener> listeners,
            ObjectProvider<ResponseErrorTranslator> translators, ObjectProvider<ObjectMapper> objectMapper,
            RestClientMeters meters, CallContextSource callContext,
            ObjectProvider<ObservationRegistry> observationRegistry,
            org.springframework.beans.factory.BeanFactory beanFactory,
            AuditSink auditSink, SensitivityClassifier sensitivity, Clock ludwigRestClientClock,
            Environment environment) {
        return new ClientRuntimeBuilder(properties, authenticators, policies,
                ordered(listeners.orderedStream().toList()),
                ordered(translators.orderedStream().toList()),
                objectMapper.getIfAvailable(ObjectMapper::new), meters, callContext,
                observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP),
                (clientName, audit) -> audit.getSink() == null
                        ? auditSink
                        : beanFactory.getBean(audit.getSink(), AuditSink.class),
                sensitivity,
                ludwigRestClientClock,
                environment.getProperty("spring.application.name", "ludwig-service"));
    }
    // CHECKSTYLE.ON: ParameterNumber

    @Bean
    @ConditionalOnMissingBean
    public NamedClientFactory ludwigNamedClientFactory(
            ObjectProvider<ObjectMapper> objectMapper, ResourceLoader resourceLoader,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<LudwigRestClientCustomizer> restCustomizers,
            ObjectProvider<LudwigWebClientCustomizer> webCustomizers,
            ObjectProvider<ClientHttpRequestInterceptor> sharedInterceptors,
            ObjectProvider<MeterRegistry> meterRegistry, RestClientProperties properties) {
        return new NamedClientFactory(
                objectMapper.getIfAvailable(ObjectMapper::new), resourceLoader,
                observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP),
                restCustomizers.orderedStream().toList(),
                webCustomizers.orderedStream().toList(),
                sharedInterceptors.orderedStream().toList(),
                meterRegistry.getIfAvailable(),
                properties.getMetrics().isPool());
    }

    @Bean
    @ConditionalOnMissingBean
    public RestClientRegistry ludwigRestClientRegistry(RestClientProperties properties,
                                                       ClientRuntimeBuilder runtimeBuilder,
                                                       NamedClientFactory clientFactory) {
        Map<String, ClientMode> modes = new LinkedHashMap<>();
        properties.getClients().forEach((name, client) ->
                modes.put(name, runtimeBuilder.resolve(name).getMode()));
        Function<String, ClientRuntime> runtimes = runtimeBuilder::build;
        return new DefaultRestClientRegistry(modes, runtimes,
                clientFactory::buildRest, clientFactory::buildWebClient);
    }

    /**
     * Refuses to start on a configuration that is individually valid and jointly wrong.
     *
     * <p>Eager, and before anything builds a client: the registry is lazy by design, so without this
     * a misconfigured client that nobody calls during a smoke test would sail through a deployment
     * and fail at 2am on the first real request.
     */
    @Bean
    @ConditionalOnMissingBean
    public RestClientConfigurationValidator ludwigRestClientConfigurationValidator(
            RestClientProperties properties, ClientRuntimeBuilder runtimeBuilder,
            Environment environment, ResourceLoader resourceLoader,
            ClientAuthenticatorFactory authenticators) {
        return new RestClientConfigurationValidator(properties, runtimeBuilder, environment,
                resourceLoader.getClassLoader(), authenticators);
    }

    private <T> List<T> ordered(List<T> beans) {
        List<T> sorted = new java.util.ArrayList<>(beans);
        sorted.sort(OrderComparator.INSTANCE);
        return List.copyOf(sorted);
    }
}
