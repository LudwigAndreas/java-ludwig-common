package ru.ludwigandreas.notification.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import ru.ludwigandreas.db.core.repository.BaseRepositoryImpl;
import ru.ludwigandreas.notification.repository.NotificationDeliveryRepository;
import ru.ludwigandreas.notification.repository.entity.NotificationDeliveryEntity;

/**
 * {@code repositoryBaseClass = BaseRepositoryImpl.class} is what gives every repository here a real
 * {@code getByIdOrThrow} implementation - without it, db-core's {@code BaseRepository} declares a
 * method Spring Data cannot implement.
 *
 * <p>The explicit {@link EntityScan} is required, not decorative: the outbox and identity-projection
 * starters each declare an {@code @EntityScan} for their own entities, and as soon as any
 * {@code @EntityScan} exists Spring Boot stops falling back to auto-configuration packages. Without
 * this line their tables would be mapped and this service's own would not.
 */
@Configuration(proxyBeanMethods = false)
@EntityScan(basePackageClasses = NotificationDeliveryEntity.class)
@EnableJpaRepositories(basePackageClasses = NotificationDeliveryRepository.class,
        repositoryBaseClass = BaseRepositoryImpl.class)
public class JpaConfig {
}
