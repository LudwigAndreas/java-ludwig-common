package ru.ludwigandreas.db.core.config;

import jakarta.persistence.EntityManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.Authentication;
import ru.ludwigandreas.db.core.audit.AuditorAwareAdapter;
import ru.ludwigandreas.db.core.audit.AuditorProvider;
import ru.ludwigandreas.db.core.audit.SpringSecurityAuditorProvider;
import ru.ludwigandreas.db.core.metrics.DbCoreMetrics;
import ru.ludwigandreas.db.core.web.DbCoreProblemMapper;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;

import java.util.Optional;

@AutoConfiguration
@ConditionalOnClass(EntityManager.class)
@EnableConfigurationProperties(DatabaseProperties.class)
public class DatabaseAutoConfiguration {

    @Bean
    @ConditionalOnClass(Authentication.class)
    @ConditionalOnMissingBean(AuditorProvider.class)
    public AuditorProvider<String> springSecurityAuditorProvider() {
        return new SpringSecurityAuditorProvider();
    }

    @Bean
    @ConditionalOnMissingBean(AuditorProvider.class)
    public AuditorProvider<String> systemAuditorProvider() {
        return () -> Optional.of("system");
    }

    @Bean
    @ConditionalOnMissingBean(AuditorAware.class)
    public AuditorAware<String> auditorAware(AuditorProvider<String> auditorProvider, DbCoreMetrics metrics) {
        return new AuditorAwareAdapter<>(auditorProvider, metrics);
    }

    /**
     * This module's exceptions, declared as meanings for the shared problem pipeline to render, and
     * the localized text for them.
     *
     * <p>Only registered when the web-core starter is on the classpath. Without it, nothing here
     * changes: these exceptions are answered by whatever advice the service has, exactly as before.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ExceptionProblemMapper.class)
    static class ProblemMappingConfiguration {

        @Bean
        @ConditionalOnMissingBean(DbCoreProblemMapper.class)
        public DbCoreProblemMapper dbCoreProblemMapper() {
            return new DbCoreProblemMapper();
        }

        @Bean
        @ConditionalOnMissingBean(name = "dbCoreProblemMessageBundle")
        public ProblemMessageBundle dbCoreProblemMessageBundle() {
            return ProblemMessageBundle.of("i18n/ludwig-db-messages");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "ludwig.db", name = "auditing-enabled", havingValue = "true", matchIfMissing = true)
    @EnableJpaAuditing(auditorAwareRef = "auditorAware")
    static class JpaAuditingConfiguration {
    }
}
