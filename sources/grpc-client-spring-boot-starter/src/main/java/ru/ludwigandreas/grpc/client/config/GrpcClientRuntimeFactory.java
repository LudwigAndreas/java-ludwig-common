package ru.ludwigandreas.grpc.client.config;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.grpc.CallCredentials;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.boot.ssl.SslBundles;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.audit.redaction.Redactor;
import ru.ludwigandreas.grpc.client.auth.TokenCallCredentials;
import ru.ludwigandreas.grpc.client.channel.ClientRuntime;
import ru.ludwigandreas.grpc.client.channel.ContractRetryPolicy;
import ru.ludwigandreas.grpc.client.channel.GrpcChannelFactory;
import ru.ludwigandreas.grpc.client.interceptor.AuthenticationInterceptor;
import ru.ludwigandreas.grpc.client.interceptor.CallRecordingInterceptor;
import ru.ludwigandreas.grpc.client.interceptor.CorrelationInterceptor;
import ru.ludwigandreas.grpc.client.interceptor.DeadlineInterceptor;
import ru.ludwigandreas.grpc.client.interceptor.FailureTranslatingInterceptor;
import ru.ludwigandreas.grpc.client.interceptor.ObservationInterceptor;
import ru.ludwigandreas.grpc.client.interceptor.ResilienceInterceptor;
import ru.ludwigandreas.grpc.client.properties.ClientSettings;
import ru.ludwigandreas.grpc.client.properties.GrpcClientProperties;
import ru.ludwigandreas.grpc.client.properties.GrpcClientPropertiesMerger;
import ru.ludwigandreas.grpc.client.spi.GrpcCallCredentialsProvider;
import ru.ludwigandreas.grpc.core.context.GrpcCallContext;
import ru.ludwigandreas.grpc.core.metadata.GrpcMetadataKeys;
import ru.ludwigandreas.grpc.core.problem.GrpcStatusCodec;
import ru.ludwigandreas.outboundtoken.CachingTokenSource;

/**
 * Builds one client: its channel, and the fixed pipeline around it.
 *
 * <pre>
 * observation -> correlation -> log/audit -> failure typing -> rate limiter -> bulkhead -> circuit breaker
 *             -> deadline -> credentials [-> gRPC retry -> send, inside the channel]
 * </pre>
 *
 * <p>Not configurable, for the reason the server's chain is not: every other order has a concrete
 * defect. Observation and the log outermost, so a refused call is measured and audited as refused;
 * the three policies around the deadline and the retries, so they count logical calls; credentials
 * innermost, so each attempt is authenticated with the current token.
 */
final class GrpcClientRuntimeFactory {

    private static final String NOT_PERMITTED = "ludwig.grpcclient.calls.notpermitted";
    private static final String TOKEN_REFRESHES = "ludwig.grpcclient.auth.token.refreshes";
    private static final String INSTANCE_PREFIX = "grpc-";

    private final GrpcClientProperties properties;
    private final Map<String, GrpcCallCredentialsProvider> providers;
    private final Dependencies dependencies;

    GrpcClientRuntimeFactory(GrpcClientProperties properties, Map<String, GrpcCallCredentialsProvider> providers,
                             Dependencies dependencies) {
        this.properties = properties;
        this.providers = providers;
        this.dependencies = dependencies;
    }

    ClientRuntime create(String name) {
        Supplier<ClientSettings> resolver = () ->
                GrpcClientPropertiesMerger.resolve(properties.getDefaults(), properties.getClients().get(name));
        ClientSettings settings = resolver.get();
        ManagedChannel channel = GrpcChannelFactory.create(
                settings, ContractRetryPolicy.serviceConfig(settings.getRetry()), dependencies.sslBundles());
        ClientRuntime runtime = new ClientRuntime(name, channel, resolver);

        Supplier<String> correlation = correlation();
        ClientSettings.Resilience resilience = settings.getResilience();
        RateLimiter rateLimiter = rateLimiter(name, resilience.getRateLimiter());
        List<ClientInterceptor> chain = new ArrayList<>();
        chain.add(new ObservationInterceptor(name, dependencies.observations()));
        if (dependencies.correlation() != null) {
            chain.add(new CorrelationInterceptor(
                    GrpcMetadataKeys.forHeader(dependencies.correlation().headerName()), correlation));
        }
        chain.add(new CallRecordingInterceptor(name, () -> recording(runtime.settings()), dependencies.meters(),
                dependencies.audit(), dependencies.redactor(), correlation, principal(), dependencies.clock()));
        chain.add(new FailureTranslatingInterceptor(name, new GrpcStatusCodec(name), correlation));
        chain.add(new ResilienceInterceptor(name, rateLimiter, bulkhead(name, resilience.getBulkhead()),
                circuitBreaker(name, resilience.getCircuitBreaker()),
                policy -> count(NOT_PERMITTED, name, "policy", policy), correlation));
        chain.add(new DeadlineInterceptor(() -> runtime.settings().getDeadline()));
        chain.add(authentication(name, settings.getAuth()));
        runtime.complete(ClientInterceptors.interceptForward(channel, chain), rateLimiter);
        return runtime;
    }

    private AuthenticationInterceptor authentication(String name, ClientSettings.Auth auth) {
        String type = auth.getType();
        return switch (type) {
            case "none" -> new AuthenticationInterceptor(null, null, false);
            case "bearer" -> new AuthenticationInterceptor(
                    new TokenCallCredentials(name, options -> auth.getToken()), null, false);
            case "oauth2-client-credentials" -> {
                // Runnable::run: gRPC applies credentials on its own executor, so the blocking path
                // is the only one used and the asynchronous mint executor is never reached.
                CachingTokenSource tokens = new CachingTokenSource(name,
                        dependencies.clientCredentials().minters().apply(name, auth), auth.getRefreshSkew(),
                        Runnable::run, dependencies.clock(), () -> count(TOKEN_REFRESHES, name, "type", type));
                yield new AuthenticationInterceptor(new TokenCallCredentials(name,
                        options -> tokens.get(options.getOption(TokenCallCredentials.FORCE_REFRESH)).value()),
                        null, true);
            }
            case "oauth2-token-relay" -> new AuthenticationInterceptor(
                    new TokenCallCredentials(name, options -> options.getOption(TokenCallCredentials.RELAY_TOKEN)),
                    relayToken(), false);
            default -> {
                CallCredentials custom = providers.get(type)
                        .create(name, auth.getProperties() == null ? Map.of() : auth.getProperties());
                yield new AuthenticationInterceptor(custom, null, false);
            }
        };
    }

    private RateLimiter rateLimiter(String name, ClientSettings.RateLimiter declared) {
        if (declared == null) {
            return null;
        }
        ClientSettings.RateLimiter limits = GrpcClientPropertiesMerger.merge(
                GrpcClientPropertiesMerger.rateLimiterDefaults(), declared);
        return dependencies.rateLimiters().rateLimiter(INSTANCE_PREFIX + name, RateLimiterConfig.custom()
                .limitForPeriod(limits.getLimitForPeriod())
                .limitRefreshPeriod(limits.getRefreshPeriod())
                .timeoutDuration(limits.getTimeout())
                .build());
    }

    private Bulkhead bulkhead(String name, ClientSettings.Bulkhead declared) {
        if (declared == null) {
            return null;
        }
        ClientSettings.Bulkhead limits = GrpcClientPropertiesMerger.merge(
                GrpcClientPropertiesMerger.bulkheadDefaults(), declared);
        return dependencies.bulkheads().bulkhead(INSTANCE_PREFIX + name, BulkheadConfig.custom()
                .maxConcurrentCalls(limits.getMaxConcurrentCalls())
                .maxWaitDuration(limits.getMaxWait())
                .build());
    }

    private CircuitBreaker circuitBreaker(String name, ClientSettings.CircuitBreaker settings) {
        if (!Boolean.TRUE.equals(settings.getEnabled())) {
            return null;
        }
        return dependencies.circuitBreakers().circuitBreaker(INSTANCE_PREFIX + name, CircuitBreakerConfig.custom()
                .failureRateThreshold(settings.getFailureRateThreshold())
                .slidingWindowSize(settings.getSlidingWindowSize())
                .minimumNumberOfCalls(settings.getMinimumNumberOfCalls())
                .waitDurationInOpenState(settings.getWaitDurationInOpenState())
                .build());
    }

    private Supplier<String> correlation() {
        Supplier<String> observed = dependencies.correlation() == null
                ? () -> null
                : dependencies.correlation().current();
        return () -> {
            String inbound = GrpcCallContext.CORRELATION_ID.get();
            return inbound != null ? inbound : observed.get();
        };
    }

    /**
     * The caller's token: the one a gRPC call being served authenticated with, else the one in the
     * security context. The first matters because the platform's principal type keeps no token.
     */
    private Supplier<String> relayToken() {
        Supplier<String> fromSecurity = dependencies.security().inboundToken();
        return () -> {
            String inbound = GrpcCallContext.INBOUND_BEARER_TOKEN.get();
            return inbound != null ? inbound : fromSecurity.get();
        };
    }

    private Supplier<String> principal() {
        return dependencies.security() == null ? () -> null : dependencies.security().principal();
    }

    private static CallRecordingInterceptor.Settings recording(ClientSettings settings) {
        return new CallRecordingInterceptor.Settings(settings.getLogging().getLevel(),
                settings.getLogging().getMaxPayloadChars(), Boolean.TRUE.equals(settings.getAudit().getEnabled()),
                settings.getAudit().getSuccessSampling());
    }

    private void count(String meter, String client, String tag, String value) {
        MeterRegistry meters = dependencies.meters();
        if (meters != null) {
            Counter.builder(meter).tag("client", client).tag(tag, value).register(meters).increment();
        }
    }

    /**
     * Everything the pipeline is built from that is not the client's own configuration. Any of the
     * nullable ones absent means that integration is off.
     */
    record Dependencies(
            ObservationRegistry observations, MeterRegistry meters, AuditSink audit, Redactor redactor,
            CircuitBreakerRegistry circuitBreakers, BulkheadRegistry bulkheads, RateLimiterRegistry rateLimiters,
            SslBundles sslBundles, Clock clock, GrpcClientIntegrations.Correlation correlation,
            GrpcClientIntegrations.Security security, GrpcClientIntegrations.ClientCredentials clientCredentials) {
    }
}
