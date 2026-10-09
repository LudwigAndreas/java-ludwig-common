package ru.ludwigandreas.odatafilter.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import ru.ludwigandreas.odatafilter.metadata.FilterMetadataRegistry;
import ru.ludwigandreas.odatafilter.testmodel.Employee;
import ru.ludwigandreas.odatafilter.web.FilterMetadataController;

/**
 * Whether the endpoint exists at all. The interesting assertions are the negative ones: a surface that is
 * absent rather than present-and-refusing is the whole of this feature's opt-in.
 */
class ODataFilterMetadataAutoConfigurationTest {

    private static EntityManagerFactory entityManagerFactoryOf(Class<?>... javaTypes) {
        Metamodel metamodel = Mockito.mock(Metamodel.class);
        Set<EntityType<?>> entities = Set.of(javaTypes).stream().map(javaType -> {
            EntityType<?> entityType = Mockito.mock(EntityType.class);
            Mockito.doReturn(javaType).when(entityType).getJavaType();
            return entityType;
        }).collect(java.util.stream.Collectors.toSet());
        Mockito.doReturn(entities).when(metamodel).getEntities();
        EntityManagerFactory factory = Mockito.mock(EntityManagerFactory.class);
        Mockito.doReturn(metamodel).when(factory).getMetamodel();
        return factory;
    }

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ODataFilterMetricsAutoConfiguration.class,
                    ODataFilterAutoConfiguration.class,
                    ODataFilterMetadataAutoConfiguration.class))
            .withBean(EntityManagerFactory.class, () -> entityManagerFactoryOf(Employee.class));

    @Test
    @DisplayName("with no base path configured there is no controller and no registry - the surface is absent")
    void absentWithoutABasePath() {
        // Not "present and refusing": an endpoint that answered 403 would still confirm the feature exists
        // and still be one misconfiguration away from answering.
        runner.run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(FilterMetadataController.class)
                .doesNotHaveBean(FilterMetadataRegistry.class));
    }

    @Test
    @DisplayName("a configured base path mounts the controller and the registry")
    void mountedWithABasePath() {
        runner.withPropertyValues("odata.filter.metadata.base-path=/api/v1/filter-metadata")
                .run(context -> assertThat(context)
                        .hasSingleBean(FilterMetadataController.class)
                        .hasSingleBean(FilterMetadataRegistry.class));
    }

    @Test
    @DisplayName("enabled=false switches it off while leaving the path configured")
    void switchedOffWithThePathStillSet() {
        // The point of having two properties: turning the feature off in one environment must not require
        // editing the path out of a shared configuration file, which is how a path gets lost.
        runner.withPropertyValues(
                        "odata.filter.metadata.base-path=/api/v1/filter-metadata",
                        "odata.filter.metadata.enabled=false")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(FilterMetadataController.class)
                        .doesNotHaveBean(FilterMetadataRegistry.class));
    }

    @Test
    @DisplayName("with no EntityManagerFactory nothing is mounted, and the context still starts")
    void absentWithoutAPersistenceUnit() {
        // An entity with no persistence unit has no endpoint to describe. This is also the condition that
        // keeps ODataFilterService usable in export-spring-boot-starter, which has no EntityManager.
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ODataFilterMetricsAutoConfiguration.class,
                        ODataFilterAutoConfiguration.class,
                        ODataFilterMetadataAutoConfiguration.class))
                .withPropertyValues("odata.filter.metadata.base-path=/api/v1/filter-metadata")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(FilterMetadataController.class));
    }

    @Test
    @DisplayName("an application can replace the controller and keep the registry")
    void controllerIsReplaceable() {
        runner.withPropertyValues("odata.filter.metadata.base-path=/api/v1/filter-metadata")
                .withBean("ownController", FilterMetadataController.class, () -> Mockito.mock(
                        FilterMetadataController.class))
                .run(context -> assertThat(context)
                        .hasSingleBean(FilterMetadataController.class)
                        .hasSingleBean(FilterMetadataRegistry.class));
    }
}
