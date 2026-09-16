package ru.ludwigandreas.db.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.ludwigandreas.db.core.web.DbCoreProblemMapper;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;

/**
 * This module's contribution to the shared problem pipeline is conditional on that pipeline being on
 * the classpath, and web-core is an optional dependency - so the interesting case is the one where it
 * is absent and this autoconfiguration still has to load.
 */
class DatabaseProblemMappingConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DbCoreMetricsAutoConfiguration.class, DatabaseAutoConfiguration.class))
            // JPA auditing needs a real metamodel; the problem-mapping contribution is independent
            // of it, and this keeps the test to the one condition it is about.
            .withPropertyValues("ludwig.db.auditing-enabled=false");

    @Test
    @DisplayName("with web-core present, the mapper and the bundle are registered")
    void contributesWhenWebCoreIsPresent() {
        runner.run(context -> assertThat(context)
                .hasSingleBean(DbCoreProblemMapper.class)
                .hasSingleBean(ProblemMessageBundle.class));
    }

    @Test
    @DisplayName("without web-core it still starts, and simply contributes nothing")
    void startsWithoutTheWebCoreStarter() {
        runner.withClassLoader(new FilteredClassLoader(
                        FilteredClassLoader.PackageFilter.of("ru.ludwigandreas.webcore")))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean("dbCoreProblemMapper")
                        .doesNotHaveBean("dbCoreProblemMessageBundle"));
    }
}
