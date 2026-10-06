package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import ru.ludwigandreas.audit.config.AuditCoreAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.PermissionEvaluator;
import ru.ludwigandreas.audit.ActorResolver;
import ru.ludwigandreas.cache.api.LudwigCacheRegistry;
import ru.ludwigandreas.cache.config.LudwigCacheAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import jakarta.servlet.Filter;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.List;
import ru.ludwigandreas.security.authn.mtls.MutualTlsAuthenticationFilter;
import ru.ludwigandreas.security.authn.mtls.PartnerIdentityResolver;
import ru.ludwigandreas.security.authz.Authorities;
import ru.ludwigandreas.security.authz.AuthorityLookup;
import ru.ludwigandreas.security.authz.AuthorityResolver;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.security.config.DataAuthorizationAutoConfiguration;
import ru.ludwigandreas.security.config.LudwigSecurityAutoConfiguration;
import ru.ludwigandreas.security.config.MutualTlsAutoConfiguration;
import ru.ludwigandreas.security.config.SecurityAuditAutoConfiguration;
import ru.ludwigandreas.security.authn.pat.PatAuthenticationFilter;
import ru.ludwigandreas.security.authn.pat.PatIntrospectionClient;
import ru.ludwigandreas.security.config.ResourceServerAutoConfiguration;
import ru.ludwigandreas.security.config.SecurityMetricsAutoConfiguration;
import ru.ludwigandreas.security.web.IdentityHeaderStrippingFilter;
import ru.ludwigandreas.security.data.DataAccessGuard;
import ru.ludwigandreas.security.data.DataScopeMapping;
import ru.ludwigandreas.security.data.DataScopePolicyValidator;
import ru.ludwigandreas.security.data.DataScopeRegistry;
import ru.ludwigandreas.security.metrics.SecurityMetrics;
import ru.ludwigandreas.security.web.SecurityProblemMapper;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;

/**
 * What the module wires up, and - more importantly - what it refuses to start with.
 *
 * <p>Uses {@code ApplicationContextRunner} rather than a full {@code @SpringBootTest} so the conditions
 * can be exercised one at a time, with no database and no identity provider.
 */
class SecurityAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    // The authority cache is cache-spring-boot-starter's now, so its auto-configuration
                    // has to be on the runner: without it the registry is missing and the authority cache
                    // bean cannot be created - which is the failure a service would also see.
                    LudwigCacheAutoConfiguration.class,
                    LudwigSecurityAutoConfiguration.class,
                    SecurityMetricsAutoConfiguration.class,
                    DataAuthorizationAutoConfiguration.class,
                    MutualTlsAutoConfiguration.class,
                    AuditCoreAutoConfiguration.class,
                    SecurityAuditAutoConfiguration.class));

    @Test
    @DisplayName("it contributes its problem mapper and bundle when web-core is present")
    void contributesToTheSharedProblemPipeline() {
        // Without this, an in-dispatch 403 would come back under web-core's generic code rather than
        // the one this module's own filter-chain handlers write for the same condition.
        runner.withPropertyValues("ludwig.security.jwt.audiences=test")
                .run(context -> assertThat(context)
                        .hasSingleBean(SecurityProblemMapper.class)
                        .hasSingleBean(ProblemMessageBundle.class));
    }

    @Test
    @DisplayName("without web-core it still starts, and simply contributes nothing")
    void startsWithoutTheWebCoreStarter() {
        // web-core is an optional dependency, so this autoconfiguration has to be loadable by a
        // class loader with no web-core jar at all. Filtering the whole package rather than one
        // class matters: the mapper and the bundle are guarded on different types from that jar,
        // and filtering only one of them would leave a classpath that cannot exist in practice.
        runner.withClassLoader(new FilteredClassLoader(
                        FilteredClassLoader.PackageFilter.of("ru.ludwigandreas.webcore")))
                .withPropertyValues("ludwig.security.jwt.audiences=test")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(AuthorityResolver.class)
                        .doesNotHaveBean("ludwigSecurityProblemMapper")
                        .doesNotHaveBean("ludwigSecurityProblemMessageBundle"));
    }

    @Test
    @DisplayName("a service that configures nothing still gets the whole authorization stack")
    void registersDefaults() {
        runner.run(context -> assertThat(context)
                .hasSingleBean(AuthorityResolver.class)
                .hasSingleBean(AuthorityLookup.class)
                .hasSingleBean(LudwigCacheRegistry.class)
                .hasSingleBean(ActorResolver.class)
                .hasSingleBean(SecurityMetrics.class)
                .hasSingleBean(DataAccessGuard.class)
                .hasSingleBean(DataScopeRegistry.class)
                .hasSingleBean(PermissionEvaluator.class));
    }

    @Test
    @DisplayName("a service's own AuthorityResolver replaces the fallback and is what the lookup wraps")
    void consumerResolverWins() {
        runner.withUserConfiguration(CustomResolverConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(AuthorityResolver.class);
            assertThat(context.getBean(AuthorityLookup.class)
                    .lookup(PrincipalRef.user("someone")).roles())
                    .containsExactly("ROLE_FROM_CONSUMER");
        });
    }

    @Test
    @DisplayName("starting without a real resolver is a failure when the service asked it to be")
    void requireResolverFailsFast() {
        runner.withPropertyValues("ludwig.security.authorities.require-resolver=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("mTLS is off unless asked for - a service with no partners carries no identity-header filter")
    void mtlsIsOptIn() {
        runner.run(context -> assertThat(context)
                .doesNotHaveBean(MutualTlsAuthenticationFilter.class)
                .doesNotHaveBean(PartnerIdentityResolver.class));
    }

    @Test
    @DisplayName("mTLS without a trusted-proxy list would make partner identity forgeable, so it fails startup")
    void mtlsWithoutTrustedProxiesFailsFast() {
        runner.withPropertyValues("ludwig.security.mtls.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("a trusted-proxy range covering everything is refused, not warned about")
    void mtlsWithWideOpenTrustedProxiesFailsFast() {
        runner.withPropertyValues(
                        "ludwig.security.mtls.enabled=true",
                        "ludwig.security.mtls.trusted-proxies=0.0.0.0/0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void mtlsWithANarrowTrustedProxyRangeStarts() {
        runner.withPropertyValues(
                        "ludwig.security.mtls.enabled=true",
                        "ludwig.security.mtls.trusted-proxies=10.4.0.0/16")
                .run(context -> assertThat(context)
                        .hasSingleBean(MutualTlsAuthenticationFilter.class)
                        .hasSingleBean(PartnerIdentityResolver.class));
    }

    @Test
    @DisplayName("two mappings claiming the same resource name would make the policy depend on bean order")
    void duplicateResourceNamesFailFast() {
        runner.withUserConfiguration(DuplicateMappingConfiguration.class)
                .run(context -> assertThat(context).hasFailed());
    }

    // --- startup validation of the policy tree --------------------------------------------------

    @Test
    @DisplayName("a policy for a resource nothing maps cannot be enforced, so the context does not start")
    void policyForAnUnmappedResourceFailsFast() {
        runner.withPropertyValues("ludwig.security.data.policies.order.read.ROLE_AGENT=OWN")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("no DataScopeMapping bean"));
    }

    @Test
    @DisplayName("a policy using a dimension the mapping does not bind is refused at startup")
    void policyWithAnUnboundDimensionFailsFast() {
        runner.withUserConfiguration(OwnerOnlyMappingConfiguration.class)
                .withPropertyValues("ludwig.security.data.policies.thing.read.ROLE_AGENT=TENANT")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("does not bind"));
    }

    @Test
    @DisplayName("a resource that is both unscoped and policed is contradictory, and one side wins silently")
    void unscopedResourceWithPoliciesFailsFast() {
        runner.withUserConfiguration(OwnerOnlyMappingConfiguration.class)
                .withPropertyValues(
                        "ludwig.security.data.unscoped-resources=thing",
                        "ludwig.security.data.policies.thing.read.ROLE_AGENT=OWN")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("never consults a policy"));
    }

    @Test
    @DisplayName("a misspelled grant token fails the deploy instead of the first request that hits it")
    void malformedGrantExpressionFailsFast() {
        runner.withUserConfiguration(OwnerOnlyMappingConfiguration.class)
                .withPropertyValues("ludwig.security.data.policies.thing.read.ROLE_AGENT=OWNN")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("OWNN"));
    }

    @Test
    void aConsistentPolicyAndMappingStart() {
        runner.withUserConfiguration(OwnerOnlyMappingConfiguration.class)
                .withPropertyValues("ludwig.security.data.policies.thing.read.ROLE_AGENT=OWN")
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(DataScopePolicyValidator.class));
    }

    // --- the forward-headers trap ---------------------------------------------------------------

    @Test
    @DisplayName("native forward headers let a peer forge its own address, so mTLS refuses to start")
    void mtlsWithNativeForwardHeadersFailsFast() {
        runner.withPropertyValues(
                        "ludwig.security.mtls.enabled=true",
                        "ludwig.security.mtls.trusted-proxies=10.4.0.0/16",
                        "server.forward-headers-strategy=native")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("RemoteIpValve"));
    }

    @Test
    void mtlsWithNativeForwardHeadersStartsOnceAcknowledged() {
        runner.withPropertyValues(
                        "ludwig.security.mtls.enabled=true",
                        "ludwig.security.mtls.trusted-proxies=10.4.0.0/16",
                        "ludwig.security.mtls.trust-native-forward-headers=true",
                        "server.forward-headers-strategy=native")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("framework forward headers are safe - the rewrite is a wrapper the module unwraps past")
    void mtlsWithFrameworkForwardHeadersStarts() {
        runner.withPropertyValues(
                        "ludwig.security.mtls.enabled=true",
                        "ludwig.security.mtls.trusted-proxies=10.4.0.0/16",
                        "server.forward-headers-strategy=framework")
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(MutualTlsAuthenticationFilter.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnerOnlyMappingConfiguration {

        record Thing(String owner) {
        }

        @Bean
        DataScopeMapping<Thing> thingMapping() {
            return DataScopeMapping.forResource("thing", Thing.class)
                    .owner(com.querydsl.core.types.dsl.Expressions.stringPath("owner"), Thing::owner)
                    .build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomResolverConfiguration {

        @Bean
        AuthorityResolver customResolver() {
            return ref -> Authorities.builder().roles(Set.of("FROM_CONSUMER")).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DuplicateMappingConfiguration {

        private record Thing(String owner) {
        }

        private DataScopeMapping<Thing> mapping() {
            return DataScopeMapping.forResource("thing", Thing.class)
                    .owner(com.querydsl.core.types.dsl.Expressions.stringPath("owner"), Thing::owner)
                    .build();
        }

        @Bean
        DataScopeMapping<Thing> firstMapping() {
            return mapping();
        }

        @Bean
        DataScopeMapping<Thing> secondMapping() {
            return mapping();
        }
    }

    /**
     * The chain the PAT filter joins, asserted against the filters Spring Security actually registered.
     *
     * <p>A separate runner because this is the only group that needs the resource server built, and
     * building it pulls in Boot's own security auto-configuration - which the rest of the class
     * deliberately does without, so that each condition can be exercised on its own.
     *
     * <p>The ordering is asserted against {@code chain.getFilters()} rather than against the
     * configuration, for a specific reason: every one of these filters is added with
     * {@code addFilterBefore(..., BasicAuthenticationFilter.class)}, so the configuration says nothing
     * about their order relative to each other - only insertion order does. A test that read the
     * configuration would pass while the filters ran in any sequence at all.
     */
    private final WebApplicationContextRunner chainRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    LudwigCacheAutoConfiguration.class,
                    LudwigSecurityAutoConfiguration.class,
                    SecurityMetricsAutoConfiguration.class,
                    DataAuthorizationAutoConfiguration.class,
                    MutualTlsAutoConfiguration.class,
                    AuditCoreAutoConfiguration.class,
                    SecurityAuditAutoConfiguration.class,
                    ResourceServerAutoConfiguration.class,
                    SecurityAutoConfiguration.class,
                    OAuth2ResourceServerAutoConfiguration.class,
                    // Spring MVC, because the chain's requestMatchers resolve through
                    // HandlerMappingIntrospector - which only exists when Security and MVC share a
                    // context. Its absence is the failure a service would see too, so it belongs here
                    // rather than being worked around with an AntPathRequestMatcher in production code.
                    HttpMessageConvertersAutoConfiguration.class,
                    WebMvcAutoConfiguration.class))
            .withPropertyValues(
                    "ludwig.security.jwt.audiences=deploy-service",
                    // A jwk-set-uri rather than an issuer-uri, so the decoder is built without the
                    // provider-discovery call an issuer-uri makes at startup.
                    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://idp.invalid/jwks");

    @Test
    @DisplayName("no PAT filter by default - adding this starter does not open a second authentication path")
    void patFilterIsOffByDefault() {
        chainRunner.run(context -> {
            assertThat(context)
                    .doesNotHaveBean(PatAuthenticationFilter.class)
                    .doesNotHaveBean(PatIntrospectionClient.class);
            assertThat(filterNames(context.getBean(SecurityFilterChain.class)))
                    .doesNotContain("PatAuthenticationFilter");
        });
    }

    @Test
    @DisplayName("enabled without an issuer URL fails startup rather than refusing every token at runtime")
    void patFilterWithoutAnIssuerFailsFast() {
        chainRunner.withPropertyValues("ludwig.security.pat.filter.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("when enabled it sits after the stripping filter, and the bearer filter declines the token")
    void patFilterSitsAfterStrippingAndTheResolverDeclinesTheCredential() {
        chainRunner.withPropertyValues(
                        "ludwig.security.pat.filter.enabled=true",
                        "ludwig.security.pat.filter.issuer-base-url=https://idp.internal")
                .run(context -> {
                    List<String> names = filterNames(context.getBean(SecurityFilterChain.class));

                    assertThat(names).contains("PatAuthenticationFilter");
                    assertThat(names.indexOf("PatAuthenticationFilter"))
                            .as("after the stripping filter, so nothing it reads can be a client-supplied"
                                    + " identity header")
                            .isGreaterThan(names.indexOf(IdentityHeaderStrippingFilter.class.getSimpleName()));
                    assertThat(names.indexOf("PatAuthenticationFilter"))
                            .as("before the filter that actually enforces the authorization rules, which is"
                                    + " what 'authenticates the request' has to mean")
                            .isLessThan(names.indexOf("AuthorizationFilter"));

                    // The assertion this group exists for, and the one that found the defect. The PAT
                    // filter is registered AFTER the bearer filter - Spring Security orders
                    // BearerTokenAuthenticationFilter ahead of BasicAuthenticationFilter, which is the
                    // position this module adds every filter at, stripping included. Since the bearer
                    // filter authenticates unconditionally and does not continue the chain on failure,
                    // reaching the PAT filter at all depends on the resolver reporting no token.
                    assertThat(names.indexOf(BearerTokenAuthenticationFilter.class.getSimpleName()))
                            .as("recorded, not desired: the real order is bearer filter first, which is why"
                                    + " PatAwareBearerTokenResolver exists")
                            .isLessThan(names.indexOf("PatAuthenticationFilter"));
                    // And that the resolver is in effect in the chain that was actually built, asserted
                    // by running the bearer filter rather than by inspecting a bean: it must pass an
                    // lpat_ credential through instead of committing a 401 on it.
                    assertThat(bearerFilterPassesThrough(context.getBean(SecurityFilterChain.class)))
                            .as("the bearer filter must decline the credential and continue the chain;"
                                    + " if it answers 401 here, the PAT filter is never reached and the"
                                    + " whole direct path is dead in production")
                            .isTrue();
                });
    }

    @Test
    @DisplayName("without the filter, the bearer filter treats an lpat_ credential as an invalid token")
    void defaultResolverIsUntouchedWithoutTheFilter() {
        // The other side of the same assertion, and the one that makes "only when enabled" true rather
        // than claimed: a deployment on the designed edge-exchange route resolves bearer tokens exactly
        // as Spring Security does, so an lpat_ credential is an undecodable JWT and is refused.
        chainRunner.run(context -> assertThat(bearerFilterPassesThrough(
                context.getBean(SecurityFilterChain.class))).isFalse());
    }

    /**
     * Runs the chain's bearer-token filter against an {@code lpat_} credential and reports whether it
     * continued the chain.
     *
     * <p>{@code false} means it committed a response - which for this input is the {@code 401} that
     * would make the direct PAT path unreachable.
     */
    private static boolean bearerFilterPassesThrough(SecurityFilterChain chain) throws Exception {
        Filter bearer = chain.getFilters().stream()
                .filter(BearerTokenAuthenticationFilter.class::isInstance)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no bearer filter in the chain"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/things");
        request.addHeader("Authorization",
                "Bearer lpat_abcdefghijk_0123456789012345678901234567890123456_x");
        MockFilterChain downstream = new MockFilterChain();
        bearer.doFilter(request, new MockHttpServletResponse(), downstream);
        return downstream.getRequest() != null;
    }

    private static List<String> filterNames(SecurityFilterChain chain) {
        return chain.getFilters().stream().map(Filter::getClass).map(Class::getSimpleName).toList();
    }

}
