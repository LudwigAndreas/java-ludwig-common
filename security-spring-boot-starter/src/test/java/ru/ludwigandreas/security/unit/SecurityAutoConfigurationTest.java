package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.PermissionEvaluator;
import ru.ludwigandreas.security.audit.AccessAuditLogger;
import ru.ludwigandreas.security.authn.mtls.MutualTlsAuthenticationFilter;
import ru.ludwigandreas.security.authn.mtls.PartnerIdentityResolver;
import ru.ludwigandreas.security.authz.Authorities;
import ru.ludwigandreas.security.authz.AuthorityCache;
import ru.ludwigandreas.security.authz.AuthorityLookup;
import ru.ludwigandreas.security.authz.AuthorityResolver;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.security.config.DataAuthorizationAutoConfiguration;
import ru.ludwigandreas.security.config.LudwigSecurityAutoConfiguration;
import ru.ludwigandreas.security.config.MutualTlsAutoConfiguration;
import ru.ludwigandreas.security.config.SecurityMetricsAutoConfiguration;
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
                    LudwigSecurityAutoConfiguration.class,
                    SecurityMetricsAutoConfiguration.class,
                    DataAuthorizationAutoConfiguration.class,
                    MutualTlsAutoConfiguration.class));

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
                .hasSingleBean(AuthorityCache.class)
                .hasSingleBean(AccessAuditLogger.class)
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
}
