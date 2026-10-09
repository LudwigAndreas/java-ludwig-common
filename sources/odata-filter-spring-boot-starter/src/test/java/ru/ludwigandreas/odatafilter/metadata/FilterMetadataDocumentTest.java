package ru.ludwigandreas.odatafilter.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.odatafilter.policy.FilterPolicyRegistry;
import ru.ludwigandreas.odatafilter.properties.ODataFilterProperties;
import ru.ludwigandreas.odatafilter.testmodel.Employee;

/** What the document says, and - more to the point - what it refuses to say. */
class FilterMetadataDocumentTest {

    private static final Set<String> ANONYMOUS = Set.of();
    private static final Set<String> ADMIN = Set.of("ROLE_ADMIN");
    private static final Set<String> HR = Set.of("ROLE_HR");

    private final FilterPolicyRegistry registry = new FilterPolicyRegistry(new ODataFilterProperties());

    private FilterMetadata document(Set<String> roles) {
        return FilterMetadataFactory.of("employee", registry.policyFor(Employee.class), roles);
    }

    private List<String> paths(Set<String> roles) {
        return document(roles).properties().stream().map(FilterPropertyMetadata::path).toList();
    }

    @Test
    @DisplayName("the entity's enforced limits are published, so a client need not discover them by failing")
    void publishesTheLimits() {
        FilterMetadata metadata = document(ANONYMOUS);

        assertThat(metadata.entity()).isEqualTo("employee");
        assertThat(metadata.maxDepth()).isEqualTo(4);
        assertThat(metadata.maxPageSize()).isEqualTo(50);
        assertThat(metadata.defaultPageSize()).isEqualTo(10);
        assertThat(metadata.maxNestedPropertyDepth()).isEqualTo(2);
        assertThat(metadata.defaultOrderBy()).isEqualTo("id asc");
    }

    @Test
    @DisplayName("an unannotated field is absent - the document projects the allow-list, not the schema")
    void omitsUnannotatedFields() {
        // secretNotes is a mapped column with no @Filterable, and shadowDepartment is an unannotated
        // association. Neither is reachable by a filter, so neither may be visible to discovery.
        assertThat(paths(ADMIN)).doesNotContain("secretNotes").doesNotContain("shadowDepartment");
        assertThat(paths(ADMIN)).contains("name", "age", "status");
    }

    @Test
    @DisplayName("a role-restricted path is absent for a caller without the role")
    void omitsPathsTheCallerMayNotUse() {
        assertThat(paths(ANONYMOUS)).doesNotContain("salary");
        assertThat(paths(ADMIN)).contains("salary");
    }

    @Test
    @DisplayName("no role name appears anywhere in the document")
    void publishesNoRoleNames() {
        // A caller learning that salary needs ROLE_ADMIN has learned a column name and a privilege name
        // from an endpoint whose purpose is to tell it less.
        assertThat(document(ADMIN).toString()).doesNotContain("ROLE_ADMIN").doesNotContain("ROLE_HR");
    }

    @Test
    @DisplayName("a restricted association hides every path beneath it, not just the association")
    void omitsTheSubtreeOfARestrictedAssociation() {
        // previousDepartment requires ROLE_HR, so previousDepartment/name must be absent too - a nested
        // path is never easier to reach than the association leading to it.
        assertThat(paths(ANONYMOUS)).noneMatch(path -> path.startsWith("previousDepartment"));
        assertThat(paths(HR)).anyMatch(path -> path.startsWith("previousDepartment"));
    }

    @Test
    @DisplayName("an open association's nested paths are published with their / separators")
    void publishesNestedPaths() {
        assertThat(paths(ANONYMOUS)).contains("department/name");
    }

    @Test
    @DisplayName("types are API-level names, so a client knows how to spell a literal")
    void publishesApiLevelTypes() {
        FilterMetadata metadata = document(ADMIN);

        assertThat(typeOf(metadata, "name")).isEqualTo("string");
        assertThat(typeOf(metadata, "age")).isEqualTo("integer");
        assertThat(typeOf(metadata, "salary")).isEqualTo("decimal");
        // Not java.math.BigDecimal: the implementation is not the client's business and tells it nothing.
        assertThat(metadata.toString()).doesNotContain("java.");
    }

    @Test
    @DisplayName("operators are published per path, lower-cased as OData spells them")
    void publishesOperatorsPerPath() {
        FilterMetadata metadata = document(ADMIN);

        assertThat(operatorsOf(metadata, "name")).contains("eq", "contains", "startswith");
        assertThat(operatorsOf(metadata, "name")).allMatch(operator -> operator.equals(operator.toLowerCase()));
    }

    @Test
    @DisplayName("paths are sorted, so two readings of one policy are byte-identical")
    void sortsPaths() {
        assertThat(paths(ADMIN)).isSorted();
    }

    private String typeOf(FilterMetadata metadata, String path) {
        return metadata.properties().stream()
                .filter(property -> path.equals(property.path()))
                .findFirst().orElseThrow()
                .type();
    }

    private List<String> operatorsOf(FilterMetadata metadata, String path) {
        return metadata.properties().stream()
                .filter(property -> path.equals(property.path()))
                .findFirst().orElseThrow()
                .operators();
    }
}
