package ru.ludwigandreas.example.catalog.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import ru.ludwigandreas.db.core.repository.BaseRepositoryImpl;
import ru.ludwigandreas.example.catalog.repository.ProductRepository;
import ru.ludwigandreas.example.catalog.repository.entity.ProductEntity;

/**
 * {@code repositoryBaseClass = BaseRepositoryImpl.class} is what gives every repository here a real
 * {@code getByIdOrThrow} implementation - without it, db-core's {@code BaseRepository} declares a
 * method Spring Data cannot implement.
 *
 * <p>The explicit {@link EntityScan} is required, not decorative: the outbox starter declares an
 * {@code @EntityScan} for its own entities, and as soon as any {@code @EntityScan} exists Spring
 * Boot stops falling back to auto-configuration packages. Without this line the outbox tables would
 * be mapped and this service's own would not.
 */
@Configuration(proxyBeanMethods = false)
@EntityScan(basePackageClasses = ProductEntity.class)
@EnableJpaRepositories(basePackageClasses = ProductRepository.class, repositoryBaseClass = BaseRepositoryImpl.class)
public class JpaConfig {
}
