package ru.ludwigandreas.restclient.config;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import ru.ludwigandreas.restclient.auth.ClientAuthenticatorFactory;
import ru.ludwigandreas.restclient.auth.provider.ApiKeyAuthenticationProvider;
import ru.ludwigandreas.restclient.auth.provider.BasicAuthenticationProvider;
import ru.ludwigandreas.restclient.auth.provider.BearerAuthenticationProvider;
import ru.ludwigandreas.restclient.auth.provider.ClientCredentialsAuthenticationProvider;
import ru.ludwigandreas.restclient.auth.provider.CustomAuthenticationProvider;
import ru.ludwigandreas.restclient.auth.provider.NoneAuthenticationProvider;
import ru.ludwigandreas.restclient.auth.provider.TokenRelayAuthenticationProvider;
import ru.ludwigandreas.restclient.auth.provider.TokenRefreshCounter;
import ru.ludwigandreas.restclient.observability.RestClientMeters;
import ru.ludwigandreas.restclient.spi.ClientAuthenticationProvider;

/**
 * Registers the authentication providers this starter ships.
 *
 * <p>Ordering is the interesting part. The built-in providers are declared here, and
 * {@link ClientAuthenticatorFactory} lets a later bean with the same {@code type()} win. Combined
 * with {@code @ConditionalOnMissingBean} on nothing in particular, that gives a service two ways to
 * change authentication: add a new {@code type}, or replace an existing one - neither requiring a
 * change here.
 *
 * <p>The OAuth2 providers are conditional on Spring Security actually being present <em>and</em> on a
 * {@code ClientRegistrationRepository} existing. Without them the type is simply unknown, and a
 * client that asks for it fails at startup with a message listing the types that are known - which
 * is far more useful than a {@code NoClassDefFoundError} from inside a provider.
 */
@AutoConfiguration(after = RestClientAutoConfiguration.class)
@ConditionalOnProperty(prefix = RestClientProperties.PREFIX, name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RestClientAuthAutoConfiguration {

    /**
     * The executor token refreshes run on when the calling thread must not block.
     *
     * <p>Two threads, daemon. Refreshes are rare - one per token lifetime per client, because
     * {@code CachingTokenSource} coalesces concurrent ones - so this is a small pool that spends its
     * life idle, and making it larger would only mean more simultaneous requests to the
     * authorization server, which is the thing being avoided.
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "ludwigTokenRefreshExecutor")
    public java.util.concurrent.ExecutorService ludwigTokenRefreshExecutor() {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "ludwig-token-refresh-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(2, factory);
    }

    @Bean
    @ConditionalOnMissingBean
    public TokenRefreshCounter ludwigTokenRefreshCounter(RestClientMeters meters) {
        return meters::tokenRefresh;
    }

    @Bean
    public ClientAuthenticationProvider ludwigNoneAuthenticationProvider() {
        return new NoneAuthenticationProvider();
    }

    @Bean
    public ClientAuthenticationProvider ludwigBasicAuthenticationProvider() {
        return new BasicAuthenticationProvider();
    }

    @Bean
    public ClientAuthenticationProvider ludwigBearerAuthenticationProvider(BeanFactory beanFactory) {
        return new BearerAuthenticationProvider(beanFactory);
    }

    @Bean
    public ClientAuthenticationProvider ludwigApiKeyAuthenticationProvider() {
        return new ApiKeyAuthenticationProvider();
    }

    @Bean
    public ClientAuthenticationProvider ludwigCustomAuthenticationProvider(BeanFactory beanFactory) {
        return new CustomAuthenticationProvider(beanFactory);
    }

    @Bean
    @ConditionalOnClass(ClientRegistrationRepository.class)
    @ConditionalOnBean(ClientRegistrationRepository.class)
    public ClientAuthenticationProvider ludwigClientCredentialsAuthenticationProvider(
            ClientRegistrationRepository registrations, Executor ludwigTokenRefreshExecutor,
            Clock ludwigRestClientClock, TokenRefreshCounter counter) {
        return new ClientCredentialsAuthenticationProvider(registrations, ludwigTokenRefreshExecutor,
                ludwigRestClientClock, counter);
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.security.oauth2.server.resource.authentication."
            + "AbstractOAuth2TokenAuthenticationToken")
    public ClientAuthenticationProvider ludwigTokenRelayAuthenticationProvider() {
        return new TokenRelayAuthenticationProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public ClientAuthenticatorFactory ludwigClientAuthenticatorFactory(
            ObjectProvider<ClientAuthenticationProvider> providers, Environment environment) {
        List<ClientAuthenticationProvider> ordered = providers.orderedStream().toList();
        return new ClientAuthenticatorFactory(ordered, environment);
    }
}
