package ru.ludwigandreas.security.config;

import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
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
 *   <li><b>Personal access token</b> next, and only when a deployment has enabled it. Same reasoning as
 *       mTLS, which is why it shares that position: a PAT request carries no bearer token and would be
 *       rejected by the resource server before the credential was ever looked at. Absent by default - the
 *       designed route is that the edge exchanges the token before it arrives, and this filter exists for
 *       a deployment whose edge cannot.</li>
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
/*
 * before, not after. SecurityAutoConfiguration, OAuth2ResourceServerAutoConfiguration and
 * OAuth2ClientAutoConfiguration each register a SecurityFilterChain guarded by
 * @ConditionalOnDefaultWebSecurity or @ConditionalOnMissingBean, which is
 * @ConditionalOnMissingBean(SecurityFilterChain.class) - so whichever is processed first wins.
 * Declared "after", this module's chain lost that race every single time, and a service configured
 * with an issuer-uri silently ran on Boot's default chain instead: no public paths, CSRF on (which
 * rejects every non-GET API call from a non-browser client), the default empty-bodied 401/403
 * instead of the localized problem documents, no mTLS filter and no identity-header stripping. The
 * module looked configured and did nothing.
 *
 * Nothing here depends on those autoconfigurations having run: the JwtDecoder is injected as an
 * ObjectProvider and resolved when the chain is built, not when it is defined.
 */
@AutoConfiguration(before = {SecurityAutoConfiguration.class, OAuth2ResourceServerAutoConfiguration.class,
        OAuth2ClientAutoConfiguration.class})
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

    /**
     * Computes, logs and bounds how long a revoked personal access token keeps working.
     *
     * <p>A bean rather than a call inside another bean's factory method, so that it runs during context
     * refresh and fails startup, in the same way and for the same reason as
     * {@link #ludwigAudienceValidator}. A warning here would be read once and then scroll away; what this
     * guards against is a window nobody computed, and nobody reads a log line about a number they never
     * thought to ask for.
     */
    @Bean
    public RevocationWindowValidator ludwigRevocationWindowValidator(
            SecurityProperties properties,
            ObjectProvider<ru.ludwigandreas.cache.config.CacheSettingsResolver> cacheSettings) {
        RevocationWindowValidator validator = new RevocationWindowValidator(
                properties.getPat(),
                RevocationWindowValidator.authorityCacheTtl(cacheSettings.getIfAvailable()),
                RevocationWindowValidator.introspectionCacheTtl(cacheSettings.getIfAvailable()));
        validator.validate();
        return validator;
    }

    /**
     * The introspection client, for a deployment whose edge cannot exchange a token.
     *
     * <p>Fails startup when the filter is enabled with no issuer URL. A filter that cannot reach the
     * issuer authenticates nothing, and discovering that at the first request looks like "personal access
     * tokens do not work" rather than like a missing property - the same reasoning as the audience check.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ludwig.security.pat.filter", name = "enabled")
    public ru.ludwigandreas.security.authn.pat.PatIntrospectionClient ludwigPatIntrospectionClient(
            SecurityProperties properties) {
        SecurityProperties.Pat.Filter filter = properties.getPat().getFilter();
        if (filter.getIssuerBaseUrl() == null || filter.getIssuerBaseUrl().isBlank()) {
            throw new SecurityConfigurationException(
                    "ludwig.security.pat.filter.enabled=true but"
                            + " ludwig.security.pat.filter.issuer-base-url is not set. The filter"
                            + " authenticates a personal access token by introspecting it at the issuing"
                            + " service; with nowhere to ask, it authenticates nothing and every token"
                            + " request is refused - which presents as 'personal access tokens do not"
                            + " work' rather than as a missing property.");
        }
        String audience = properties.getJwt().getAudiences().stream().findFirst().orElse(null);
        return new ru.ludwigandreas.security.authn.pat.PatIntrospectionClient(
                filter.getIssuerBaseUrl(), filter.getIntrospectionPath(), audience, filter.getTimeout());
    }

    /**
     * The introspection cache's declaration, published only when the filter is enabled.
     *
     * <p>Conditional rather than unconditional so that a deployment not using this path does not acquire a
     * cache it never reads - and so that {@code ludwig.cache.caches.pat-introspection} appearing in a
     * configuration dump means somebody turned the path on.
     */
    @Bean
    @ConditionalOnProperty(prefix = "ludwig.security.pat.filter", name = "enabled")
    public ru.ludwigandreas.cache.api.CacheDefinition<String,
            ru.ludwigandreas.pat.introspection.PatIntrospectionResponse> ludwigPatIntrospectionCache() {
        return ru.ludwigandreas.security.authn.pat.PatIntrospectionCaches.definition();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ludwig.security.pat.filter", name = "enabled")
    public ru.ludwigandreas.security.authn.pat.PatAuthenticationFilter ludwigPatAuthenticationFilter(
            ru.ludwigandreas.security.authn.pat.PatIntrospectionClient client,
            ru.ludwigandreas.cache.api.LudwigCacheRegistry cacheRegistry,
            AuthorityLookup authorityLookup,
            SecurityMetrics metrics) {
        return new ru.ludwigandreas.security.authn.pat.PatAuthenticationFilter(
                client,
                cacheRegistry.cache(
                        ru.ludwigandreas.security.authn.pat.PatIntrospectionCaches.definition()),
                authorityLookup,
                metrics);
    }

    // SUPPRESS CHECKSTYLE ParameterNumber - nine collaborators, and each one is a separately
    // conditional bean this method has to resolve lazily: the JWT decoder and its converter exist only
    // when an issuer is configured, the mTLS filter only when mTLS is on, the PAT filter only when a
    // deployment enabled it, and the trusted proxies only alongside mTLS. Grouping them into a holder
    // would mean a type whose entire purpose is to carry nine beans into one method, plus a bean
    // definition for the holder - and it would hide which of them a context test has to substitute,
    // which is the thing this signature is good at making obvious.
    @SuppressWarnings("checkstyle:ParameterNumber")
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
            ObjectProvider<ru.ludwigandreas.security.authn.pat.PatAuthenticationFilter> patFilter,
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

        // Added after the stripping filter and at the same position, so it runs after it: the request
        // reaching here has had every identity header removed.
        //
        // NOT before the bearer-token filter, which is what this was first written to do and what the
        // mTLS filter's argument would suggest. Spring Security orders BearerTokenAuthenticationFilter
        // AHEAD of BasicAuthenticationFilter, so this position is after the bearer filter, and the
        // stripping filter above is too - moving this one earlier would mean moving that one as well.
        // And it would not have been enough: the bearer filter does not check for an existing
        // authentication, so it would have fed the lpat_ credential to the JWT decoder, failed, and
        // answered 401 regardless of what ran before it. PatAwareBearerTokenResolver below is what
        // actually makes this path reachable; see its javadoc, and SecurityAutoConfigurationTest, which
        // asserts the real order rather than this comment.
        //
        // Absent unless ludwig.security.pat.filter.enabled, so this is null for every deployment whose
        // edge can perform the token exchange, which is the designed route.
        ru.ludwigandreas.security.authn.pat.PatAuthenticationFilter pat = patFilter.getIfAvailable();
        if (pat != null) {
            http.addFilterBefore(pat, BasicAuthenticationFilter.class);
        }

        JwtDecoder decoder = jwtDecoder.getIfAvailable();
        JwtPrincipalConverter converter = jwtConverter.getIfAvailable();
        if (decoder != null && converter != null) {
            http.oauth2ResourceServer(oauth2 -> {
                oauth2.authenticationEntryPoint(entryPoint)
                        .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter));
                if (pat != null) {
                    oauth2.bearerTokenResolver(
                            new ru.ludwigandreas.security.authn.pat.PatAwareBearerTokenResolver(
                                    new DefaultBearerTokenResolver()));
                }
            });
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
