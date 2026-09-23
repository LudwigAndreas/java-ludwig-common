package ru.ludwigandreas.identity.config;

import jakarta.persistence.EntityManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import ru.ludwigandreas.db.core.repository.BaseRepositoryImpl;
import ru.ludwigandreas.identity.authority.DatabaseAuthorityResolver;
import ru.ludwigandreas.identity.authority.DatabaseDataScopeProvider;
import ru.ludwigandreas.identity.authority.DatabasePartnerIdentityResolver;
import ru.ludwigandreas.identity.entity.SecurityUserEntity;
import ru.ludwigandreas.identity.kafka.OidcUserEventMapper;
import ru.ludwigandreas.identity.kafka.OidcUserEventMapperImpl;
import ru.ludwigandreas.identity.projection.IdentityProjectionService;
import ru.ludwigandreas.identity.repository.SecurityGrantRepository;
import ru.ludwigandreas.identity.repository.SecurityPartnerRepository;
import ru.ludwigandreas.identity.repository.SecurityUserRepository;
import ru.ludwigandreas.security.authn.mtls.PartnerIdentityResolver;
import ru.ludwigandreas.security.authz.AuthorityCache;
import ru.ludwigandreas.security.authz.AuthorityResolver;
import ru.ludwigandreas.security.config.SecurityProperties;
import ru.ludwigandreas.security.data.DataScopeProvider;

/**
 * Wires the projection: its entities and repositories, and the three security SPIs it implements.
 *
 * <p>Because these are registered as the security module's SPI types, adding this dependency is all a
 * service has to do - {@code security-spring-boot-starter} backs its own fallbacks off automatically
 * ({@code @ConditionalOnMissingBean}) and starts resolving roles from the projection instead.
 */
@AutoConfiguration
@ConditionalOnClass(EntityManager.class)
@ConditionalOnProperty(prefix = "ludwig.identity", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(IdentityProjectionProperties.class)
public class IdentityProjectionAutoConfiguration {

    /**
     * MapStruct generates the implementation as a {@code @Component}, but a consumer's component scan
     * never reaches this module's packages - so the generated class is registered explicitly here, the
     * same way the repositories and entities are.
     */
    @Bean
    @ConditionalOnMissingBean(OidcUserEventMapper.class)
    public OidcUserEventMapper oidcUserEventMapper() {
        return new OidcUserEventMapperImpl();
    }

    @Bean
    @ConditionalOnMissingBean(IdentityProjectionService.class)
    public IdentityProjectionService identityProjectionService(SecurityUserRepository users,
                                                                OidcUserEventMapper mapper,
                                                                AuthorityCache authorityCache,
                                                                IdentityProjectionProperties properties) {
        return new IdentityProjectionService(users, mapper, authorityCache,
                properties.getContact().isEnabled());
    }

    @Bean
    @ConditionalOnMissingBean(AuthorityResolver.class)
    public AuthorityResolver databaseAuthorityResolver(SecurityUserRepository users,
                                                        SecurityPartnerRepository partners,
                                                        IdentityProjectionProperties properties) {
        return new DatabaseAuthorityResolver(users, partners, properties);
    }

    @Bean
    @ConditionalOnMissingBean(DatabaseDataScopeProvider.class)
    @ConditionalOnProperty(prefix = "ludwig.identity", name = "grants-enabled", matchIfMissing = true)
    public DataScopeProvider databaseDataScopeProvider(SecurityGrantRepository grants) {
        return new DatabaseDataScopeProvider(grants);
    }

    @Bean
    @ConditionalOnMissingBean(PartnerIdentityResolver.class)
    @ConditionalOnProperty(prefix = "ludwig.identity", name = "partner-registry-enabled",
            matchIfMissing = true)
    public PartnerIdentityResolver databasePartnerIdentityResolver(SecurityPartnerRepository partners,
                                                                    SecurityProperties securityProperties) {
        return new DatabasePartnerIdentityResolver(partners, securityProperties);
    }

    /**
     * Registers this module's entities and repositories additively, without disturbing the consuming
     * application's own {@code @EntityScan}/{@code @EnableJpaRepositories} - so a service using the module
     * needs no scanning configuration of its own.
     */
    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = SecurityUserEntity.class)
    @EnableJpaRepositories(basePackageClasses = SecurityUserRepository.class,
            repositoryBaseClass = BaseRepositoryImpl.class)
    static class IdentityJpaConfiguration {
    }
}
