package ru.ludwigandreas.fileaction.config;

import jakarta.persistence.EntityManager;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import ru.ludwigandreas.db.core.repository.BaseRepositoryImpl;
import ru.ludwigandreas.fileaction.entity.FileActionSubmissionEntity;
import ru.ludwigandreas.fileaction.repository.FileActionSubmissionRepository;

/**
 * Makes this module's two entities and their repositories visible to a consuming application.
 *
 * <h2>Why this is separate from {@link FileActionAutoConfiguration}</h2>
 *
 * <p>Entity and repository scanning have to happen before the beans that inject a repository are created. And a
 * consuming application that declares its own {@code @EntityScan} <em>replaces</em> the default rather than adding
 * to it, so a module's entities have to be registered explicitly or they vanish the moment the application names
 * its own packages - which presents as "the table exists but the repository has no entity".
 *
 * <h2>Why {@code repositoryBaseClass} is named</h2>
 *
 * <p>{@code db-core}'s {@code BaseRepository} declares methods - {@code getByIdOrThrow} and friends - that only its
 * own implementation provides. Without naming the base class, Spring Data tries to derive a query from the method
 * name and the context fails at startup with a message about a property called "throw". Every persistence-carrying
 * starter here names it, and {@code idempotency}'s copy of this class records the same failure - which is where
 * this one was fixed from, after {@code FileActionSchemaIT} hit it.
 *
 * <p>The scanning is scoped to this module's own packages. Scanning wider from a library is how a starter comes to
 * own the consuming application's entity discovery and then breaks it by adding a package.
 */
@AutoConfiguration
@ConditionalOnClass(EntityManager.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(prefix = "ludwig.file-action", name = "enabled", matchIfMissing = true)
@EntityScan(basePackageClasses = FileActionSubmissionEntity.class)
@EnableJpaRepositories(basePackageClasses = FileActionSubmissionRepository.class,
        repositoryBaseClass = BaseRepositoryImpl.class)
public class FileActionPersistenceAutoConfiguration {
}
