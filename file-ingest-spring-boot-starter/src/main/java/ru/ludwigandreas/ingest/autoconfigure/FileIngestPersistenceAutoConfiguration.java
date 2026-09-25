package ru.ludwigandreas.ingest.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import ru.ludwigandreas.db.core.repository.BaseRepositoryImpl;
import ru.ludwigandreas.ingest.entity.FileIngestRun;
import ru.ludwigandreas.ingest.repository.FileIngestRunRepository;

/**
 * Makes this module's two tables visible to a service that scans only its own packages.
 *
 * <p>Entity scanning and repository scanning are both rooted at the application class by default, so
 * a starter's entities are invisible unless it says where they are. Declared here rather than in
 * {@link FileIngestAutoConfiguration} because these annotations register bean definitions
 * unconditionally, and a service that switched the module off with {@code ludwig.ingest.enabled=false}
 * should not thereby acquire two repositories it cannot use.
 *
 * <p>{@code repositoryBaseClass = BaseRepositoryImpl.class} is required rather than optional:
 * {@code BaseRepository} declares {@code getByIdOrThrow}, which is not a derived query and cannot be
 * one - without the base class Spring Data tries to parse the method name and fails at context
 * refresh with "no property 'throw'", which is a confusing way to find out about a missing line of
 * configuration.
 *
 * <p>{@code @EnableTransactionManagement} is here because {@code BatchCommitter}'s
 * {@code @Transactional} is the module's central invariant: without a proxy, its
 * {@code REQUIRES_NEW} would be a no-op and the batch and the checkpoint would commit separately - the
 * exact failure that class exists to prevent, arriving silently.
 */
@AutoConfiguration
@EnableTransactionManagement
@EntityScan(basePackageClasses = FileIngestRun.class)
@EnableJpaRepositories(basePackageClasses = FileIngestRunRepository.class,
        repositoryBaseClass = BaseRepositoryImpl.class)
@ConditionalOnProperty(prefix = "ludwig.ingest", name = "enabled", matchIfMissing = true)
public class FileIngestPersistenceAutoConfiguration {
}
