package ru.ludwigandreas.pat.autoconfigure;

import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ru.ludwigandreas.pat.service.PatService;
import ru.ludwigandreas.pat.web.PatCredentialGuard;
import ru.ludwigandreas.pat.web.PatManagementController;

/**
 * Mounts the management API and the credential guard in front of it.
 *
 * <p>The guard is registered for the management base path specifically, read from the same property the
 * controller's mappings use. Registering it for every path would make it an authentication-wide policy -
 * "no token-backed caller may reach any endpoint" - which is the opposite of the point: token-backed
 * callers are supposed to reach the rest of the application, that is what tokens are for. This one surface
 * is where they must not go.
 *
 * <p>Conditional on the property rather than unconditional, because the issuing API belongs in exactly one
 * service (the identity provider) while the verifying side belongs everywhere. A service that depends on
 * this starter for its entities but should not expose issuance sets
 * {@code ludwig.pat.web.enabled=false}, which is a configuration decision an auditor can read rather than a
 * classpath accident.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({DispatcherServlet.class, WebMvcConfigurer.class})
@ConditionalOnProperty(prefix = "ludwig.pat", name = "enabled", matchIfMissing = true)
public class PatWebAutoConfiguration {

    /** The configured base path, which the guard's pattern and the controller's mappings both derive from. */
    private static final String BASE_PATH_PROPERTY = "ludwig.pat.web.base-path";

    private static final String DEFAULT_BASE_PATH = "/api/v1/personal-access-tokens";

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ludwig.pat.web", name = "enabled", matchIfMissing = true)
    public PatManagementController ludwigPatManagementController(PatService service, Clock clock) {
        return new PatManagementController(service, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public PatCredentialGuard ludwigPatCredentialGuard() {
        return new PatCredentialGuard();
    }

    /**
     * The module's message bundle, so the problem documents above are localized.
     *
     * <p>Registered as a {@link ru.ludwigandreas.webcore.problem.ProblemMessageBundle} rather than by the
     * application adding a basename, so that adopting this starter cannot leave the caller reading raw
     * message keys.
     */
    @Bean
    @ConditionalOnMissingBean(name = "ludwigPatProblemMessageBundle")
    public ru.ludwigandreas.webcore.problem.ProblemMessageBundle ludwigPatProblemMessageBundle() {
        return ru.ludwigandreas.webcore.problem.ProblemMessageBundle.of("i18n/ludwig-pat-messages");
    }

    /**
     * Registers the guard for the management paths and nothing else.
     *
     * <p>A {@code WebMvcConfigurer} rather than the application having to add the interceptor itself, so
     * that the guard cannot be forgotten by a deployment that adds this starter and writes no configuration.
     * A security control a consumer has to opt into is a security control half of them will not have.
     */
    @Bean
    @ConditionalOnProperty(prefix = "ludwig.pat.web", name = "enabled", matchIfMissing = true)
    public WebMvcConfigurer ludwigPatGuardRegistration(PatCredentialGuard guard, Environment environment) {
        String basePath = environment.getProperty(BASE_PATH_PROPERTY, DEFAULT_BASE_PATH);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(guard)
                        .addPathPatterns(basePath, basePath + "/**");
            }
        };
    }
}
