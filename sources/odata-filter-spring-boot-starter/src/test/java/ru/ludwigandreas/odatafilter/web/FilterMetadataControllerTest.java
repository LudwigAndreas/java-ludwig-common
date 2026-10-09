package ru.ludwigandreas.odatafilter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import ru.ludwigandreas.odatafilter.exception.FilterMetadataNotPublishedException;
import ru.ludwigandreas.odatafilter.metadata.FilterMetadata;
import ru.ludwigandreas.odatafilter.metadata.FilterMetadataRegistry;
import ru.ludwigandreas.odatafilter.metadata.FilterPropertyMetadata;
import ru.ludwigandreas.odatafilter.metrics.NoopODataFilterMetrics;
import ru.ludwigandreas.odatafilter.policy.FilterPolicyRegistry;
import ru.ludwigandreas.odatafilter.properties.ODataFilterProperties;
import ru.ludwigandreas.odatafilter.security.FilterPrincipalResolver;
import ru.ludwigandreas.odatafilter.testmodel.Department;
import ru.ludwigandreas.odatafilter.testmodel.Employee;

/** Who gets which answer, and what an unpublished name gets. */
class FilterMetadataControllerTest {

    private static Metamodel metamodelOf(Class<?>... javaTypes) {
        Metamodel metamodel = Mockito.mock(Metamodel.class);
        Set<EntityType<?>> entities = List.of(javaTypes).stream().map(javaType -> {
            EntityType<?> entityType = Mockito.mock(EntityType.class);
            Mockito.doReturn(javaType).when(entityType).getJavaType();
            return entityType;
        }).collect(Collectors.toSet());
        Mockito.doReturn(entities).when(metamodel).getEntities();
        return metamodel;
    }

    private final ODataFilterProperties properties = new ODataFilterProperties();
    private final List<String> served = new ArrayList<>();

    private FilterMetadataController controller(Set<String> callerRoles) {
        FilterPrincipalResolver resolver = () -> callerRoles;
        return new FilterMetadataController(
                new FilterMetadataRegistry(metamodelOf(Employee.class, Department.class)),
                new FilterPolicyRegistry(properties),
                resolver,
                new NoopODataFilterMetrics() {
                    @Override
                    public void recordMetadataServed(String entityType) {
                        served.add(entityType);
                    }
                });
    }

    private List<String> paths(FilterMetadata metadata) {
        return metadata.properties().stream().map(FilterPropertyMetadata::path).toList();
    }

    @Test
    @DisplayName("a published entity is served under its published name, not its class name")
    void servesAPublishedEntity() {
        FilterMetadata metadata = controller(Set.of()).metadata("employee");

        assertThat(metadata.entity()).isEqualTo("employee");
        assertThat(paths(metadata)).contains("name", "age");
    }

    @Test
    @DisplayName("a role-restricted path is absent for a caller without the role and present for one with it")
    void filtersByCallerRoles() {
        assertThat(paths(controller(Set.of()).metadata("employee"))).doesNotContain("salary");
        assertThat(paths(controller(Set.of("ROLE_ADMIN")).metadata("employee"))).contains("salary");
    }

    @Test
    @DisplayName("an entity that publishes nothing is a 404, not an empty document")
    void refusesAnUnpublishedEntity() {
        // Department is a real entity in the metamodel with @Filterable fields of its own, and declares no
        // metadataName. An empty document would confirm it exists, which turns discovery into entity-model
        // enumeration.
        assertThatThrownBy(() -> controller(Set.of()).metadata("department"))
                .isInstanceOf(FilterMetadataNotPublishedException.class);
        assertThatThrownBy(() -> controller(Set.of()).metadata("Department"))
                .isInstanceOf(FilterMetadataNotPublishedException.class);
    }

    @Test
    @DisplayName("an unknown name gets the same answer as an unpublished entity")
    void refusesAnUnknownName() {
        assertThatThrownBy(() -> controller(Set.of()).metadata("nothing-like-this"))
                .isInstanceOf(FilterMetadataNotPublishedException.class)
                .hasMessageContaining("nothing-like-this");
    }

    @Test
    @DisplayName("serving a document is counted, so probing can be told from asking properly")
    void countsWhatItServes() {
        controller(Set.of()).metadata("employee");

        assertThat(served).containsExactly("employee");
    }

    @Test
    @DisplayName("a refused request is not counted as served")
    void doesNotCountARefusal() {
        assertThatThrownBy(() -> controller(Set.of()).metadata("nothing-like-this"))
                .isInstanceOf(FilterMetadataNotPublishedException.class);

        assertThat(served).isEmpty();
    }

    @Test
    @DisplayName("the endpoint needs no entity type in its signature")
    void exposesNoEntityType() throws Exception {
        // web.controllers-do-not-expose-entities inspects type arguments too, so a document holding a Class
        // or a JPA type would be caught there. Pinned here as well because this is the controller that most
        // obviously wants to hold one.
        Class<?> returnType = FilterMetadataController.class
                .getMethod("metadata", String.class).getReturnType();

        assertThat(returnType).isEqualTo(FilterMetadata.class);
        assertThat(FilterMetadata.class.getRecordComponents())
                .noneMatch(component -> Class.class.equals(component.getType()));
    }
}
