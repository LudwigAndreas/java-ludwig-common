package ru.ludwigandreas.job.core.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.ludwigandreas.cache.api.LudwigCacheRegistry;
import ru.ludwigandreas.job.core.admin.service.CacheEvictControl;
import ru.ludwigandreas.job.core.config.CacheAdminAutoConfiguration;

/** The caches area fails closed: without the cache starter or the replica channel it does not exist at all. */
class CacheAdminAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CacheAdminAutoConfiguration.class));

    @Test
    @DisplayName("without a cache registry, the administrative surface or the replica channel nothing is registered")
    void absentWithoutItsPrerequisites() {
        runner.run(context -> assertThat(context).doesNotHaveBean(CacheEvictControl.class));
        runner.withClassLoader(new FilteredClassLoader(LudwigCacheRegistry.class))
                .run(context -> assertThat(context).doesNotHaveBean(CacheEvictControl.class));
    }
}
