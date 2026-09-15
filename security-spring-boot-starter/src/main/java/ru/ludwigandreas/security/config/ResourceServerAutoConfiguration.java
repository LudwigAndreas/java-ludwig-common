package ru.ludwigandreas.security.config;

import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import ru.ludwigandreas.security.authn.jwt.AudienceValidatingJwtDecoderPostProcessor;
import ru.ludwigandreas.security.authn.jwt.JwtPrincipalConverter;
import ru.ludwigandreas.security.authn.mtls.MutualTlsAuthenticationFilter;
import ru.ludwigandreas.security.authn.mtls.TrustedProxies;
import ru.ludwigandreas.security.authz.AuthorityLookup;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;
import ru.ludwigandreas.security.metrics.SecurityMetrics;
import ru.ludwigandreas.security.web.IdentityHeaderStrippingFilter;
import ru.ludwigandreas.security.web.SecurityHeaders;
import ru.ludwigandreas.security.web.SecurityMdcFilter;

/**
 * The HTTP filter chain: who is allowed in, in what order the identities are established, and how a
 * rejection is rendered.
 *
 * <p>The order the filters run in is the interesting part, and it is not arbitrary:
 *
 * <ol>
 *   <li><b>Header stripping</b> first, so nothing later can read a client-supplied identity header.</li>
 *   <li><b>mTLS</b> next, because a partner request carries no bearer token and would otherwise be
 *       rejected by the resource server before its certificate was ever looked at.</li>
 *   <li><b>Bearer token</b> last, for browser users, and only if nothing has authenticated yet.</li>
 * </ol>
 *
 * <p>The chain is stateless and CSRF protection is off, and both follow from where the session lives.
 * The browser's session cookie never reaches this service: the edge holds the session and exchanges it
 * for a short-lived JWT per request. A service that receives no ambient credential cannot be the target
 * of a cross-site request forgery - the attacker's page can make the browser issue a request, but it
 * cannot make the edge attach a token to it. CSRF protection therefore belongs at the edge, on the
 * cookie-to-token exchange, and duplicating it here would only break API clients.
 *
 * <p>Backs off entirely if the service declares its own {@link SecurityFilterChain} - at which point
 * the beans above are still available and can be wired in by hand.
 */
@lombok.extern.slf4j.Slf4j
@AutoConfiguration(after = OAuth2ResourceServerAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(SecurityFilterChain.class)
@ConditionalOnProperty(prefix = "ludwig.security", name = "enabled", matchIfMissing = true)
public class ResourceServerAutoConfiguration {

    private static final String ISSUER_URI_PROPERTY = "spring.security.oauth2.resourceserver.jwt.issuer-uri";

    @Bean
    @ConditionalOnMissingBean(JwtPrincipalConverter.class)
    @ConditionalOnProperty(prefix = "ludwig.security.jwt", name = "enabled", matchIfMissing = true)
    public JwtPrincipalConverter ludwigJwtPrincipalConverter(AuthorityLookup authorityLookup,
                                                              SecurityProperties properties,
                                                              SecurityMetrics metrics) {
        return new JwtPrincipalConverter(authorityLookup, properties, metrics);
    }

    /**
     * Fails startup when audience validation is demanded but no audience is configured anywhere. A
     * resource server that skips the audience check accepts any token the issuer minted for any
     * service, so this is worth refusing to start over rather than warning about.
     */
    @Bean
    @ConditionalOnProperty(prefix = "ludwig.security.jwt", name = "enabled", matchIfMissing = true)
    public AudienceValidatingJwtDecoderPostProcessor ludwigAudienceValidator(SecurityProperties properties,
                                                                             Environment environment) {
        SecurityProperties.Jwt jwt = properties.getJwt();
        Set<String> audiences = Set.copyOf(jwt.getAudiences());
        boolean bootAudiencesConfigured = environment.containsProperty(
                "spring.security.oauth2.resourceserver.jwt.audiences");
        if (jwt.isRequireAudience() && audiences.isEmpty() && !bootAudiencesConfigured) {
            throw new SecurityConfigurationException(
                    "ludwig.security.jwt.require-audience=true but no audience is configured. Set "
                            + "ludwig.security.jwt.audiences to this service's audience, or turn the "
                            + "requirement off deliberately. Without it, a token minted for any other "
                            + "service that trusts the same issuer is accepted here.");
        }
        return new AudienceValidatingJwtDecoderPostProcessor(
                environment.getProperty(ISSUER_URI_PROPERTY), audiences);
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain ludwigSecurityFilterChain(
            HttpSecurity http,
            SecurityProperties properties,
            AuthenticationEntryPoint entryPoint,
            AccessDeniedHandler accessDeniedHandler,
            ObjectProvider<JwtDecoder> jwtDecoder,
            ObjectProvider<JwtPrincipalConverter> jwtConverter,
            ObjectProvider<MutualTlsAuthenticationFilter> mtlsFilter,
            ObjectProvider<TrustedProxies> trustedProxies) throws Exception {

        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(requests -> {
                    warnAboutWideOpenPublicPaths(properties.getPublicPaths());
                    properties.getPublicPaths().forEach(path -> requests.requestMatchers(path).permitAll());
                    // Coarse gate only. Which roles and which rows are decided per endpoint by method
                    // security and per query by the data scope - expressing that here would scatter the
                    // policy across a configuration class nobody reads next to the code it protects.
                    requests.anyRequest().authenticated();
                });

        MutualTlsAuthenticationFilter mutualTls = mtlsFilter.getIfAvailable();

        if (properties.isStripIdentityHeaders()) {
            // When the mTLS filter is active it owns x-forwarded-client-cert: stripping the header here
            // would hide a forged one from the filter that is meant to reject it, turning a spoofing
            // attempt into an ordinary 401 with no warning and no metric. With no mTLS filter there is
            // nothing to own it, so it is stripped like any other identity header.
            List<String> stripped = mutualTls == null
                    ? SecurityHeaders.CLIENT_FORBIDDEN
                    : SecurityHeaders.CLIENT_FORBIDDEN.stream()
                            .filter(header -> !SecurityHeaders.XFCC.equalsIgnoreCase(header))
                            .toList();
            http.addFilterBefore(new IdentityHeaderStrippingFilter(stripped, trustedProxies.getIfAvailable()),
                    BasicAuthenticationFilter.class);
        }

        // Added after the stripping filter and at the same position, so it runs after it: the request
        // reaching here has had every identity header removed except the one this filter validates.
        if (mutualTls != null) {
            http.addFilterBefore(mutualTls, BasicAuthenticationFilter.class);
        }

        JwtDecoder decoder = jwtDecoder.getIfAvailable();
        JwtPrincipalConverter converter = jwtConverter.getIfAvailable();
        if (decoder != null && converter != null) {
            http.oauth2ResourceServer(oauth2 -> oauth2
                    .authenticationEntryPoint(entryPoint)
                    .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter)));
        }

        // After authentication, so the MDC carries the resolved principal rather than nothing.
        http.addFilterAfter(new SecurityMdcFilter(), AnonymousAuthenticationFilter.class);

        return http.build();
    }

    /**
     * A wildcard in {@code public-paths} makes the whole service anonymous, and it is the kind of entry
     * that gets added to unblock a health check and never removed. Warned about rather than refused: a
     * genuinely public API is a legitimate configuration, and this module has no way to tell the two
     * apart - but nobody should be able to say afterwards that nothing flagged it.
     */
    private void warnAboutWideOpenPublicPaths(List<String> publicPaths) {
        publicPaths.stream()
                .filter(path -> "/**".equals(path.trim()) || "/*".equals(path.trim()))
                .findFirst()
                .ifPresent(path -> log.warn("ludwig.security.public-paths contains '{}', which permits "
                        + "every request without authentication. If that is not deliberate, narrow it - "
                        + "every @PreAuthorize and every data scope behind it becomes unreachable.", path));
    }
}
