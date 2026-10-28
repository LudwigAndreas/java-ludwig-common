package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.audit.config.AuditCoreAutoConfiguration;
import ru.ludwigandreas.cache.config.LudwigCacheAutoConfiguration;
import ru.ludwigandreas.security.admin.AdminAccess;
import ru.ludwigandreas.security.admin.AdminOperation;
import ru.ludwigandreas.security.admin.AdminOperationCatalog;
import ru.ludwigandreas.security.admin.AdminOperationContributor;
import ru.ludwigandreas.security.admin.AdminSurfaceGuard;
import ru.ludwigandreas.security.config.AdminAutoConfiguration;
import ru.ludwigandreas.security.config.DataAuthorizationAutoConfiguration;
import ru.ludwigandreas.security.config.LudwigSecurityAutoConfiguration;
import ru.ludwigandreas.security.config.SecurityAuditAutoConfiguration;
import ru.ludwigandreas.security.config.SecurityMetricsAutoConfiguration;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;
import ru.ludwigandreas.webcore.config.WebCoreProblemAutoConfiguration;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;

/**
 * Every misconfiguration of the administrative surface that has to be a startup failure rather than a 403.
 */
class AdminStartupChecksTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    LudwigCacheAutoConfiguration.class,
                    LudwigSecurityAutoConfiguration.class,
                    SecurityMetricsAutoConfiguration.class,
                    DataAuthorizationAutoConfiguration.class,
                    AuditCoreAutoConfiguration.class,
                    SecurityAuditAutoConfiguration.class,
                    WebCoreProblemAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class,
                    WebMvcAutoConfiguration.class,
                    AdminAutoConfiguration.class))
            .withPropertyValues("ludwig.security.jwt.enabled=false")
            .withBean("adminFixtureMessages", ProblemMessageBundle.class,
                    () -> ProblemMessageBundle.of("i18n/admin-fixture-messages"));

    @Configuration(proxyBeanMethods = false)
    static class Operations {
        @Bean
        AdminOperationContributor fixtureOperations() {
            return () -> List.of(AdminOperation.of("fixture.read", "k.r"), AdminOperation.of("fixture.operate", "k.o"),
                    AdminOperation.of("fixture.privileged", "k.p"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PartialArea {
        @Bean
        AdminOperationContributor partialOperations() {
            return () -> List.of(AdminOperation.of("messaging.read", "k.r"),
                    AdminOperation.of("messaging.operate", "k.o"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Undescribed {
        @Bean
        AdminOperationContributor undescribedOperations() {
            return () -> List.of(AdminOperation.of("widgets.read", "ludwig.widgets.admin.read"),
                    AdminOperation.of("widgets.operate", "ludwig.widgets.admin.operate"),
                    AdminOperation.of("widgets.privileged", "k.p"));
        }
    }

    @RestController
    static class MistypedController {
        @GetMapping("/admin/v1/fixture/things")
        @AdminAccess("fixture.raed")
        public String things() {
            return "things";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Mistyped {
        @Bean
        MistypedController mistypedController() {
            return new MistypedController();
        }
    }

    @Test
    @DisplayName("a correctly configured service starts with the catalog, the grants and the guard")
    void starts() {
        runner.withUserConfiguration(Operations.class)
                .withPropertyValues("ludwig.admin.grants.fixture.read[0]=NOTIFICATION_ADMIN")
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(AdminOperationCatalog.class)
                        .hasSingleBean(AdminSurfaceGuard.class));
    }

    @Test
    @DisplayName("an area declaring only some of its tiers fails startup")
    void partialArea() {
        runner.withUserConfiguration(PartialArea.class).run(context -> assertThat(context).getFailure()
                .rootCause().isInstanceOf(SecurityConfigurationException.class)
                .hasMessageContaining("messaging.privileged"));
    }

    @Test
    @DisplayName("a misspelled grant key fails startup")
    void misspelledGrant() {
        runner.withUserConfiguration(Operations.class)
                .withPropertyValues("ludwig.admin.grants.fixture.opreate[0]=NOTIFICATION_ADMIN")
                .run(context -> assertThat(context).getFailure()
                        .rootCause().isInstanceOf(SecurityConfigurationException.class)
                        .hasMessageContaining("'fixture.opreate'"));
    }

    @Test
    @DisplayName("an operation whose description is in no bundle fails startup, naming every such operation and key")
    void undescribedOperation() {
        runner.withUserConfiguration(Undescribed.class).run(context -> assertThat(context)
                .getFailure().isInstanceOf(SecurityConfigurationException.class)
                .hasMessageContaining("widgets.read (ludwig.widgets.admin.read)")
                .hasMessageContaining("widgets.operate (ludwig.widgets.admin.operate)")
                .message().doesNotContain("widgets.privileged"));
    }

    @Test
    @DisplayName("a handler naming an uncatalogued operation fails startup, naming the handler and the id")
    void uncataloguedHandler() {
        // Thrown from afterSingletonsInstantiated, so it is the failure itself rather than a wrapped cause.
        runner.withUserConfiguration(Operations.class, Mistyped.class).run(context -> assertThat(context)
                .getFailure().isInstanceOf(SecurityConfigurationException.class)
                .hasMessageContaining("fixture.raed")
                .hasMessageContaining("MistypedController"));
    }

    @Test
    @DisplayName("a public path that matches the surface fails startup, whether written narrowly or as /**")
    void publicPaths() {
        for (String path : List.of("/admin/**", "/admin/v1/me/operations", "/**")) {
            runner.withUserConfiguration(Operations.class)
                    .withPropertyValues("ludwig.security.public-paths[0]=" + path)
                    .run(context -> assertThat(context).getFailure()
                            .rootCause().isInstanceOf(SecurityConfigurationException.class)
                            .hasMessageContaining(path));
        }
        runner.withUserConfiguration(Operations.class)
                .withPropertyValues("ludwig.security.public-paths[0]=/actuator/health")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("without web-core there is no administrative surface, and the module still starts")
    void withoutWebCore() {
        // web-core's own auto-configuration is left off the runner: with its package filtered out it could not be
        // on a real classpath either.
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LudwigCacheAutoConfiguration.class,
                        LudwigSecurityAutoConfiguration.class, SecurityMetricsAutoConfiguration.class,
                        DataAuthorizationAutoConfiguration.class, AuditCoreAutoConfiguration.class,
                        SecurityAuditAutoConfiguration.class, WebMvcAutoConfiguration.class,
                        AdminAutoConfiguration.class))
                .withPropertyValues("ludwig.security.jwt.enabled=false")
                .withClassLoader(
                        new FilteredClassLoader(FilteredClassLoader.PackageFilter.of("ru.ludwigandreas.webcore")))
                .withUserConfiguration(Operations.class)
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(AdminOperationCatalog.class)
                        .doesNotHaveBean(AdminSurfaceGuard.class));
    }

    @Test
    @DisplayName("with data authorization (and so method security) off, the surface still evaluates its grants")
    void independentOfMethodSecurity() {
        runner.withUserConfiguration(Operations.class)
                .withPropertyValues("ludwig.security.data.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(AdminSurfaceGuard.class));
    }
}
