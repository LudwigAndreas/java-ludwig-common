package ru.ludwigandreas.security.config;

import java.util.Locale;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.security.web.AuthenticationEntryPoint;
import ru.ludwigandreas.security.authn.mtls.MutualTlsAuthenticationFilter;
import ru.ludwigandreas.security.authn.mtls.PartnerIdentityResolver;
import ru.ludwigandreas.security.authn.mtls.PropertiesPartnerIdentityResolver;
import ru.ludwigandreas.security.authn.mtls.TrustedProxies;
import ru.ludwigandreas.security.authz.AuthorityLookup;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;
import ru.ludwigandreas.security.metrics.SecurityMetrics;

/**
 * Partner and service-to-service authentication from client certificates. Off unless
 * {@code ludwig.security.mtls.enabled=true}, because a service with no external partners should not
 * carry a filter that reads identity headers at all.
 */
@AutoConfiguration(before = ResourceServerAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "ludwig.security.mtls", name = "enabled", havingValue = "true")
public class MutualTlsAutoConfiguration {

    private static final String FORWARD_HEADERS_STRATEGY = "server.forward-headers-strategy";

    /**
     * Constructing this validates the CIDR list, so a wide-open one fails startup, not a request. The
     * forward-headers check below is part of the same idea: both are ways the trusted-proxy decision can
     * be made meaningless, and neither is visible by reading the mTLS configuration alone.
     */
    @Bean
    @ConditionalOnMissingBean(TrustedProxies.class)
    public TrustedProxies ludwigTrustedProxies(SecurityProperties properties, Environment environment) {
        rejectUnsafeForwardHeadersStrategy(properties, environment);
        return new TrustedProxies(properties.getMtls().getTrustedProxies());
    }

    /**
     * Refuses the one combination in which the trusted-proxy check cannot be trusted.
     *
     * <p>{@code native} puts Tomcat's {@code RemoteIpValve} in front of every filter, rewriting the
     * request's remote address from {@code X-Forwarded-For}. Its default {@code internal-proxies} covers
     * the whole private address space, so inside a cluster any workload can set that header to the
     * sidecar's address, satisfy {@code trusted-proxies}, and have a forged client-certificate header
     * believed - partner impersonation from an unauthenticated request, with nothing in the mTLS
     * configuration hinting at it.
     *
     * <p>{@code framework} is fine: that rewrite happens in a request wrapper, and
     * {@link TrustedProxies#peerAddressOf} unwraps past it to the address the container actually saw.
     */
    private void rejectUnsafeForwardHeadersStrategy(SecurityProperties properties, Environment environment) {
        String strategy = environment.getProperty(FORWARD_HEADERS_STRATEGY);
        boolean isNative = strategy != null && "native".equals(strategy.trim().toLowerCase(Locale.ROOT));
        if (isNative && !properties.getMtls().isTrustNativeForwardHeaders()) {
            throw new SecurityConfigurationException(
                    "ludwig.security.mtls.enabled=true together with " + FORWARD_HEADERS_STRATEGY
                            + "=native is unsafe: Tomcat's RemoteIpValve rewrites the remote address from "
                            + "the client-supplied X-Forwarded-For header before any filter runs, and "
                            + "trusts that header from any private-range peer by default - so the "
                            + "trusted-proxies check can be satisfied by an attacker, who can then forge "
                            + "x-forwarded-client-cert. Use forward-headers-strategy=framework, or narrow "
                            + "server.tomcat.remoteip.internal-proxies to the proxies you actually trust "
                            + "and set ludwig.security.mtls.trust-native-forward-headers=true.");
        }
    }

    @Bean
    @ConditionalOnMissingBean(PartnerIdentityResolver.class)
    public PartnerIdentityResolver ludwigPartnerIdentityResolver(SecurityProperties properties) {
        return new PropertiesPartnerIdentityResolver(properties);
    }

    @Bean
    @ConditionalOnMissingBean(MutualTlsAuthenticationFilter.class)
    public MutualTlsAuthenticationFilter ludwigMutualTlsAuthenticationFilter(
            PartnerIdentityResolver identityResolver,
            AuthorityLookup authorityLookup,
            TrustedProxies trustedProxies,
            AuthenticationEntryPoint entryPoint,
            SecurityMetrics metrics) {
        return new MutualTlsAuthenticationFilter(identityResolver, authorityLookup, trustedProxies,
                entryPoint, metrics);
    }
}
