package ru.ludwigandreas.job.core.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.cache.api.LudwigCacheRegistry;
import ru.ludwigandreas.job.core.admin.CacheAdminOperations;
import ru.ludwigandreas.job.core.admin.service.CacheAdminService;
import ru.ludwigandreas.job.core.admin.service.CacheEvictControl;
import ru.ludwigandreas.job.core.admin.web.CacheAdminController;

/**
 * The {@code caches} area and the {@code cacheevict} control, where the cache starter, the administrative surface
 * and job-core's replica-control channel all run. Here rather than in the cache module, which stays free of in-repo
 * dependencies.
 */
@AutoConfiguration(after = ReplicaControlAutoConfiguration.class,
        afterName = {"ru.ludwigandreas.cache.config.LudwigCacheAutoConfiguration",
            "ru.ludwigandreas.security.config.AdminAutoConfiguration"})
@ConditionalOnClass(name = {"ru.ludwigandreas.cache.api.LudwigCacheRegistry",
    "ru.ludwigandreas.security.admin.AdminOperationCatalog"})
@ConditionalOnBean(type = {"ru.ludwigandreas.cache.api.LudwigCacheRegistry",
    "ru.ludwigandreas.security.admin.AdminOperationCatalog",
    "ru.ludwigandreas.job.core.config.ReplicaControlAutoConfiguration$ReplicaControls"})
public class CacheAdminAutoConfiguration {

    @Bean
    public CacheAdminOperations ludwigCacheAdminOperations() {
        return new CacheAdminOperations();
    }

    @Bean
    @ConditionalOnMissingBean
    public CacheAdminService ludwigCacheAdminService(LudwigCacheRegistry registry) {
        return new CacheAdminService(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public CacheEvictControl ludwigCacheEvictControl(LudwigCacheRegistry registry) {
        return new CacheEvictControl(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public CacheAdminController ludwigCacheAdminController(CacheAdminService service) {
        return new CacheAdminController(service);
    }
}
