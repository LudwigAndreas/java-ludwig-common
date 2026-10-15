package ru.ludwigandreas.grpc.server.config;

import ru.ludwigandreas.grpc.server.server.GrpcServerConfigurationValidator;
import ru.ludwigandreas.grpc.server.error.GrpcServerMessages;
import ru.ludwigandreas.grpc.server.properties.GrpcServerProperties;
import io.grpc.Server;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.grpc.ServerInterceptor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import ru.ludwigandreas.grpc.core.problem.GrpcStatusCodec;
import ru.ludwigandreas.grpc.server.context.GrpcCallPreferenceSource;
import ru.ludwigandreas.grpc.server.context.IdempotencyKeyContributor;
import ru.ludwigandreas.grpc.server.context.PreferenceContributor;
import ru.ludwigandreas.grpc.server.interceptor.AccessLogInterceptor;
import ru.ludwigandreas.grpc.server.interceptor.AdmissionInterceptor;
import ru.ludwigandreas.grpc.server.interceptor.BuiltInServerInterceptor;
import ru.ludwigandreas.grpc.server.interceptor.CallContextContributors;
import ru.ludwigandreas.grpc.server.interceptor.ContextBridgeInterceptor;
import ru.ludwigandreas.grpc.server.interceptor.ExceptionTranslatingInterceptor;
import ru.ludwigandreas.grpc.server.interceptor.GrpcProblemRenderer;
import ru.ludwigandreas.grpc.server.interceptor.ObservationInterceptor;
import ru.ludwigandreas.grpc.server.server.GrpcServerLifecycle;
import ru.ludwigandreas.grpc.server.spi.GrpcCallContextContributor;
import ru.ludwigandreas.grpc.server.spi.LudwigGrpcServerInterceptor;
import ru.ludwigandreas.webcore.config.WebCorePreferenceAutoConfiguration;
import ru.ludwigandreas.webcore.config.WebCoreProblemAutoConfiguration;
import ru.ludwigandreas.webcore.config.WebCoreProperties;
import ru.ludwigandreas.webcore.preference.UserPreferenceResolver;
import ru.ludwigandreas.webcore.problem.ProblemDetailFactory;
import ru.ludwigandreas.webcore.problem.ProblemMapperRegistry;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;

/**
 * The gRPC server: the lifecycle, the stages of the chain that need no optional module, and the
 * assembly of the chain itself.
 *
 * <p>Everything that names a type from an optional module - Spring Security, observability,
 * idempotency - lives in its own auto-configuration class behind {@code @ConditionalOnClass}, and
 * reaches this one only as a {@link BuiltInServerInterceptor} or a
 * {@link GrpcCallContextContributor}. That is what lets a service serve gRPC without, say, the
 * idempotency module and never load a class that mentions it.
 *
 * <p>{@code ludwig.grpc.server.enabled=false} removes all of it, so nothing binds.
 */
@AutoConfiguration(after = {WebCoreProblemAutoConfiguration.class, WebCorePreferenceAutoConfiguration.class})
@ConditionalOnClass(Server.class)
@ConditionalOnProperty(prefix = GrpcServerProperties.PREFIX, name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(GrpcServerProperties.class)
public class GrpcServerAutoConfiguration {

    @Bean
    public ProblemMessageBundle ludwigGrpcServerProblemMessageBundle() {
        return ProblemMessageBundle.of(GrpcServerMessages.BUNDLE);
    }

    @Bean
    @ConditionalOnMissingBean
    public GrpcProblemRenderer ludwigGrpcProblemRenderer(
            ProblemMapperRegistry registry, ProblemDetailFactory problems,
            @Value("${spring.application.name:application}") String applicationName) {
        return new GrpcProblemRenderer(registry, problems, new GrpcStatusCodec(applicationName));
    }

    @Bean
    public GrpcCallPreferenceSource ludwigGrpcCallPreferenceSource() {
        return new GrpcCallPreferenceSource();
    }

    @Bean
    @ConditionalOnBean(UserPreferenceResolver.class)
    public PreferenceContributor ludwigGrpcPreferenceContributor(UserPreferenceResolver resolver,
                                                                 WebCoreProperties properties) {
        WebCoreProperties.Preferences preferences = properties.getPreferences();
        return new PreferenceContributor(resolver, preferences.getTimeZoneHeader(), preferences.isAcceptLanguage());
    }

    @Bean
    public IdempotencyKeyContributor ludwigGrpcIdempotencyKeyContributor() {
        return new IdempotencyKeyContributor();
    }

    @Bean
    public CallContextContributors ludwigGrpcCallContextContributors(
            ObjectProvider<GrpcCallContextContributor> contributors) {
        return new CallContextContributors(contributors.stream().toList());
    }

    @Bean
    public ExceptionTranslatingInterceptor ludwigGrpcExceptionTranslatingInterceptor(
            GrpcProblemRenderer renderer, CallContextContributors contributors) {
        return new ExceptionTranslatingInterceptor(renderer, contributors);
    }

    @Bean
    public ObservationInterceptor ludwigGrpcObservationInterceptor(ObjectProvider<ObservationRegistry> registry) {
        return new ObservationInterceptor(registry.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    @Bean
    public AccessLogInterceptor ludwigGrpcAccessLogInterceptor(ObjectProvider<MeterRegistry> meters) {
        return new AccessLogInterceptor(meters.getIfAvailable());
    }

    @Bean
    public AdmissionInterceptor ludwigGrpcAdmissionInterceptor() {
        return new AdmissionInterceptor();
    }

    @Bean
    public ContextBridgeInterceptor ludwigGrpcContextBridgeInterceptor(CallContextContributors contributors) {
        return new ContextBridgeInterceptor(contributors);
    }

    @Bean
    @ConditionalOnMissingBean
    public GrpcServerConfigurationValidator ludwigGrpcServerConfigurationValidator(
            GrpcServerProperties properties, Environment environment, ObjectProvider<SslBundles> sslBundles) {
        return new GrpcServerConfigurationValidator(properties, environment, sslBundles);
    }

    /**
     * The server, behind the chain: every built-in stage in {@link BuiltInServerInterceptor.Stage}
     * order, then the service's own interceptors in theirs. A service cannot place one of its own
     * before a built-in stage, which is the point.
     */
    @Bean
    @ConditionalOnMissingBean
    public GrpcServerLifecycle ludwigGrpcServerLifecycle(
            GrpcServerProperties properties, ListableBeanFactory beans,
            ObjectProvider<BuiltInServerInterceptor> builtIn,
            ObjectProvider<LudwigGrpcServerInterceptor> custom,
            GrpcServerConfigurationValidator validator, ObjectProvider<SslBundles> sslBundles,
            ObjectProvider<ApplicationAvailability> availability) {
        List<BuiltInServerInterceptor> stages = builtIn.stream()
                .sorted(Comparator.comparing(BuiltInServerInterceptor::stage))
                .toList();
        List<ServerInterceptor> chain = new ArrayList<>(stages);
        chain.addAll(custom.orderedStream().toList());
        boolean authenticates = stages.stream()
                .anyMatch(stage -> stage.stage() == BuiltInServerInterceptor.Stage.AUTHENTICATION);
        return new GrpcServerLifecycle(properties, beans, chain, authenticates, validator, sslBundles, availability);
    }
}
