package ru.ludwigandreas.grpc.client.config;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.audit.redaction.Redactor;
import ru.ludwigandreas.grpc.client.GrpcChannelRegistry;
import ru.ludwigandreas.grpc.client.channel.ClientRuntime;
import ru.ludwigandreas.grpc.client.channel.DefaultGrpcChannelRegistry;
import ru.ludwigandreas.grpc.client.error.GrpcClientProblemMapper;
import ru.ludwigandreas.grpc.client.properties.GrpcClientProperties;
import ru.ludwigandreas.grpc.client.spi.GrpcCallCredentialsProvider;
import ru.ludwigandreas.observability.config.ObservabilityProperties;
import ru.ludwigandreas.observability.correlation.CorrelationContext;
import ru.ludwigandreas.outboundtoken.ClientCredentialsTokenMinter;
import ru.ludwigandreas.outboundtoken.InboundBearerToken;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;

/**
 * Named gRPC clients. With no client configured nothing is built and no channel is opened.
 *
 * <p>Each optional module reaches the pipeline through one {@link GrpcClientIntegrations} record
 * created in its own nested configuration behind {@code @ConditionalOnClass}, so a service without
 * observability, Spring Security or the OAuth2 client loads no class that names them.
 */
@AutoConfiguration(afterName = {
    "ru.ludwigandreas.webcore.config.WebCoreProblemAutoConfiguration",
    "ru.ludwigandreas.observability.config.ObservabilityCoreAutoConfiguration",
    "ru.ludwigandreas.audit.config.AuditCoreAutoConfiguration",
    "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration",
    "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration",
    "org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration",
    "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration"})
@ConditionalOnProperty(prefix = GrpcClientProperties.PREFIX, name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(GrpcClientProperties.class)
public class GrpcClientAutoConfiguration {

    /**
     * Static: a registry post-processor must exist before any other bean of this class.
     */
    @Bean
    public static GrpcClientBeanRegistrar ludwigGrpcClientBeanRegistrar() {
        return new GrpcClientBeanRegistrar();
    }

    @Bean
    public ExceptionProblemMapper ludwigGrpcClientProblemMapper() {
        return new GrpcClientProblemMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public GrpcClientConfigurationValidator ludwigGrpcClientConfigurationValidator(Environment environment) {
        return new GrpcClientConfigurationValidator(environment);
    }

    /**
     * Validates every client, then builds them. Channels connect lazily, on the first call.
     */
    @Bean
    @ConditionalOnMissingBean
    @SuppressWarnings("checkstyle:ParameterNumber")
    public GrpcChannelRegistry ludwigGrpcChannelRegistry(
            GrpcClientProperties properties, GrpcClientConfigurationValidator validator,
            ObjectProvider<GrpcCallCredentialsProvider> providers,
            ObjectProvider<ObservationRegistry> observations, ObjectProvider<MeterRegistry> meters,
            ObjectProvider<AuditSink> audit, ObjectProvider<Redactor> redactor,
            ObjectProvider<CircuitBreakerRegistry> circuitBreakers, ObjectProvider<BulkheadRegistry> bulkheads,
            ObjectProvider<RateLimiterRegistry> rateLimiters, ObjectProvider<SslBundles> sslBundles,
            ObjectProvider<Clock> clock, ObjectProvider<GrpcClientIntegrations.Correlation> correlation,
            ObjectProvider<GrpcClientIntegrations.Security> security,
            ObjectProvider<GrpcClientIntegrations.ClientCredentials> clientCredentials) {
        Map<String, GrpcCallCredentialsProvider> byType = providers.stream()
                .collect(Collectors.toMap(GrpcCallCredentialsProvider::type, Function.identity()));
        validator.validate(properties, byType.keySet(), security.getIfAvailable(),
                clientCredentials.getIfAvailable() != null, sslBundles.getIfAvailable());
        GrpcClientRuntimeFactory factory = new GrpcClientRuntimeFactory(properties, byType,
                new GrpcClientRuntimeFactory.Dependencies(
                        observations.getIfAvailable(() -> ObservationRegistry.NOOP), meters.getIfAvailable(),
                        audit.getIfAvailable(), redactor.getIfAvailable(),
                        circuitBreakers.getIfAvailable(CircuitBreakerRegistry::ofDefaults),
                        bulkheads.getIfAvailable(BulkheadRegistry::ofDefaults),
                        rateLimiters.getIfAvailable(RateLimiterRegistry::ofDefaults),
                        sslBundles.getIfAvailable(), clock.getIfAvailable(Clock::systemUTC),
                        correlation.getIfAvailable(), security.getIfAvailable(), clientCredentials.getIfAvailable()));
        Map<String, ClientRuntime> clients = new LinkedHashMap<>();
        properties.getClients().keySet().forEach(name -> clients.put(name, factory.create(name)));
        return new DefaultGrpcChannelRegistry(clients);
    }

    /** Correlation ids, from observability. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(CorrelationContext.class)
    static class CorrelationConfiguration {

        @Bean
        @ConditionalOnBean(CorrelationContext.class)
        GrpcClientIntegrations.Correlation ludwigGrpcClientCorrelation(CorrelationContext correlation,
                                                                       ObservabilityProperties properties) {
            return new GrpcClientIntegrations.Correlation(properties.getCorrelation().getHeaderName(),
                    () -> correlation.currentId().orElse(null));
        }
    }

    /** The caller's identity and token, from Spring Security. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(value = {SecurityContextHolder.class, JwtDecoder.class}, name =
            "org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken")
    static class SecurityConfiguration {

        @Bean
        GrpcClientIntegrations.Security ludwigGrpcClientSecurity(ObjectProvider<JwtDecoder> decoder) {
            return new GrpcClientIntegrations.Security(InboundBearerToken::current, () -> {
                Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                return authentication == null || !authentication.isAuthenticated() ? null : authentication.getName();
            }, decoder.getIfAvailable() != null);
        }
    }

    /** Machine tokens, from the OAuth2 client. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ClientRegistrationRepository.class)
    static class ClientCredentialsConfiguration {

        @Bean
        @ConditionalOnBean(ClientRegistrationRepository.class)
        GrpcClientIntegrations.ClientCredentials ludwigGrpcClientCredentials(
                ClientRegistrationRepository registrations) {
            return new GrpcClientIntegrations.ClientCredentials((client, auth) -> new ClientCredentialsTokenMinter(
                    ClientCredentialsTokenMinter.registration(registrations, auth.getRegistrationId()),
                    auth.getScopes()));
        }
    }
}
