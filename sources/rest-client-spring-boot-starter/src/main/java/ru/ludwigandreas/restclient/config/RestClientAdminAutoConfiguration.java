package ru.ludwigandreas.restclient.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.restclient.admin.RestClientAdminOperations;
import ru.ludwigandreas.restclient.admin.service.RestClientAdminService;
import ru.ludwigandreas.restclient.admin.service.RestClientBreakerControl;
import ru.ludwigandreas.restclient.admin.service.RestClientBreakersControl;
import ru.ludwigandreas.restclient.admin.service.RestClientTokenResetControl;
import ru.ludwigandreas.restclient.admin.web.RestClientAdminController;
import ru.ludwigandreas.restclient.auth.provider.ClientTokenSources;
import ru.ludwigandreas.restclient.core.RestClientRegistry;
import ru.ludwigandreas.restclient.resilience.ResilienceRegistries;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;

/**
 * The {@code restclient} area and its three controls, where the administrative surface and job-core's
 * replica-control channel both run. Without either nothing here exists, and the starter is exactly what it was.
 */
@AutoConfiguration(after = {RestClientAutoConfiguration.class, RestClientAuthAutoConfiguration.class},
        afterName = {"ru.ludwigandreas.security.config.AdminAutoConfiguration",
            "ru.ludwigandreas.job.core.config.ReplicaControlAutoConfiguration"})
@ConditionalOnClass(name = {"ru.ludwigandreas.security.admin.AdminSurfaceGuard",
    "ru.ludwigandreas.job.core.control.ReplicaControl", "ru.ludwigandreas.webcore.problem.ProblemMessageBundle"})
@ConditionalOnBean(type = {"ru.ludwigandreas.restclient.core.RestClientRegistry",
    "ru.ludwigandreas.restclient.config.ClientRuntimeBuilder",
    "ru.ludwigandreas.restclient.resilience.ResilienceRegistries",
    "ru.ludwigandreas.restclient.auth.provider.ClientTokenSources",
    "ru.ludwigandreas.security.admin.AdminSurfaceGuard",
    "ru.ludwigandreas.job.core.config.ReplicaControlAutoConfiguration$ReplicaControls"})
public class RestClientAdminAutoConfiguration {

    @Bean
    public RestClientAdminOperations ludwigRestClientAdminOperations() {
        return new RestClientAdminOperations();
    }

    @Bean
    public ProblemMessageBundle ludwigRestClientAdminMessages() {
        return ProblemMessageBundle.of("i18n/ludwig-restclient-messages");
    }

    @Bean
    @ConditionalOnMissingBean
    public RestClientAdminService ludwigRestClientAdminService(RestClientRegistry clients,
                                                               ClientRuntimeBuilder configuration) {
        return new RestClientAdminService(clients, configuration);
    }

    @Bean
    @ConditionalOnMissingBean
    public RestClientTokenResetControl ludwigRestClientTokenResetControl(RestClientAdminService clients,
                                                                         ClientTokenSources tokens) {
        return new RestClientTokenResetControl(clients, tokens);
    }

    @Bean
    @ConditionalOnMissingBean
    public RestClientBreakersControl ludwigRestClientBreakersControl(ResilienceRegistries registries) {
        return new RestClientBreakersControl(registries);
    }

    @Bean
    @ConditionalOnMissingBean
    public RestClientBreakerControl ludwigRestClientBreakerControl(RestClientAdminService clients,
                                                                   ResilienceRegistries registries) {
        return new RestClientBreakerControl(clients, registries);
    }

    @Bean
    @ConditionalOnMissingBean
    public RestClientAdminController ludwigRestClientAdminController(RestClientAdminService service) {
        return new RestClientAdminController(service);
    }
}
