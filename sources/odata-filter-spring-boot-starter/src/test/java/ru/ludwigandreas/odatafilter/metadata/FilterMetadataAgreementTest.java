package ru.ludwigandreas.odatafilter.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.odatafilter.ast.FilterNode;
import ru.ludwigandreas.odatafilter.exception.ODataFilterException;
import ru.ludwigandreas.odatafilter.parser.ODataFilterParser;
import ru.ludwigandreas.odatafilter.parser.OrderByTerm;
import ru.ludwigandreas.odatafilter.policy.EntityFilterPolicy;
import ru.ludwigandreas.odatafilter.policy.FilterPolicyRegistry;
import ru.ludwigandreas.odatafilter.properties.ODataFilterProperties;
import ru.ludwigandreas.odatafilter.testmodel.Employee;
import ru.ludwigandreas.odatafilter.validation.FieldAccessValidator;

/**
 * The document and the query enforcement must agree: everything advertised works, and nothing omitted
 * does.
 *
 * <h2>Why this is a test and not a rule</h2>
 *
 * <p>It asserts that two <em>computations</em> produce consistent answers - the projection in
 * {@link FilterMetadataFactory} and the enforcement in {@link FieldAccessValidator}. That is a property of
 * their output for a given input, not a fact about types, packages, method signatures or source text, so
 * neither ArchUnit (which reads bytecode structure) nor Checkstyle (which reads source text) can express
 * it. This repository's convention is that such a rule is encoded as the check that can exist, and this is
 * it.
 *
 * <p>The negative half is the important half. A future change that advertised a path without opening it -
 * or kept advertising one after closing it - would leave every positive assertion green.
 */
class FilterMetadataAgreementTest {

    /** Role sets chosen to straddle every boundary Employee's policy draws. */
    private static final List<Set<String>> CALLERS = List.of(
            Set.of(),
            Set.of("ROLE_ADMIN"),
            Set.of("ROLE_HR"),
            Set.of("ROLE_ADMIN", "ROLE_HR"),
            Set.of("ROLE_UNRELATED"));

    /** Every path Employee's policy knows about, whoever may use it. */
    private static final List<String> ALL_PATHS =
            new FilterPolicyRegistry(new ODataFilterProperties())
                    .policyFor(Employee.class).fields().keySet().stream().sorted().toList();

    private final ODataFilterParser parser = new ODataFilterParser();
    private final EntityFilterPolicy policy =
            new FilterPolicyRegistry(new ODataFilterProperties()).policyFor(Employee.class);

    private FilterMetadata document(Set<String> roles) {
        return FilterMetadataFactory.of("employee", policy, roles);
    }

    @Test
    @DisplayName("every path the document advertises is accepted by the validator for that same caller")
    void advertisedPathsAreAccepted() {
        for (Set<String> caller : CALLERS) {
            for (FilterPropertyMetadata property : document(caller).properties()) {
                FilterNode node = parser.parse(filterFor(property));
                assertThatCode(() -> FieldAccessValidator.validate(policy, node, caller))
                        .as("caller %s was advertised %s and must be allowed to use it", caller, property.path())
                        .doesNotThrowAnyException();
            }
        }
    }

    @Test
    @DisplayName("every operator the document advertises on a path is accepted on that path")
    void advertisedOperatorsAreAccepted() {
        for (Set<String> caller : CALLERS) {
            for (FilterPropertyMetadata property : document(caller).properties()) {
                for (String operator : property.operators()) {
                    String filter = expressionFor(property, operator);
                    if (filter == null) {
                        continue;
                    }
                    FilterNode node = parser.parse(filter);
                    assertThatCode(() -> FieldAccessValidator.validate(policy, node, caller))
                            .as("caller %s was advertised '%s %s'", caller, property.path(), operator)
                            .doesNotThrowAnyException();
                }
            }
        }
    }

    @Test
    @DisplayName("every path the document omits is refused by the validator for that same caller")
    void omittedPathsAreRefused() {
        for (Set<String> caller : CALLERS) {
            List<String> advertised = document(caller).properties().stream()
                    .map(FilterPropertyMetadata::path)
                    .toList();
            for (String path : ALL_PATHS) {
                if (advertised.contains(path) || isAssociation(path)) {
                    continue;
                }
                FilterNode node = parser.parse(path + " eq 'x'");
                assertThatThrownBy(() -> FieldAccessValidator.validate(policy, node, caller))
                        .as("caller %s was NOT advertised %s and must not be able to use it", caller, path)
                        .isInstanceOf(ODataFilterException.class);
            }
        }
    }

    @Test
    @DisplayName("every sortable path the document advertises is accepted in $orderby")
    void advertisedSortablePathsAreAccepted() {
        for (Set<String> caller : CALLERS) {
            List<OrderByTerm> terms = document(caller).properties().stream()
                    .filter(FilterPropertyMetadata::sortable)
                    .filter(property -> !isAssociation(property.path()))
                    .map(property -> new OrderByTerm(property.path(), false))
                    .toList();
            assertThatCode(() -> FieldAccessValidator.validateOrderBy(policy, terms, caller))
                    .as("caller %s was advertised these as sortable: %s", caller, terms)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("a caller with no roles sees strictly less than an admin, and never more")
    void privilegeOnlyWidensTheDocument() {
        List<String> anonymous = document(Set.of()).properties().stream()
                .map(FilterPropertyMetadata::path).toList();
        List<String> admin = document(Set.of("ROLE_ADMIN")).properties().stream()
                .map(FilterPropertyMetadata::path).toList();

        assertThat(admin).containsAll(anonymous);
        assertThat(admin).hasSizeGreaterThan(anonymous.size());
    }

    /**
     * An association is a path segment rather than a comparable value, so {@code department eq ...} is
     * never a legal filter and asserting on it would be asserting the wrong thing.
     */
    private boolean isAssociation(String path) {
        return ALL_PATHS.stream().anyMatch(other -> other.startsWith(path + "/"));
    }

    private String filterFor(FilterPropertyMetadata property) {
        return isAssociation(property.path())
                ? firstNestedPathUnder(property.path()) + " eq 'x'"
                : literalComparison(property);
    }

    private String firstNestedPathUnder(String path) {
        return ALL_PATHS.stream().filter(other -> other.startsWith(path + "/")).findFirst().orElseThrow();
    }

    private String literalComparison(FilterPropertyMetadata property) {
        return property.path() + " eq " + sampleLiteral(property.type());
    }

    /** A literal of the shape the document says the path takes - which is the document's own claim. */
    private String sampleLiteral(String type) {
        return switch (type) {
            case "integer" -> "1";
            case "decimal" -> "1.5";
            case "boolean" -> "true";
            case "date" -> "2024-01-15";
            case "date-time" -> "2024-01-15T10:30:00Z";
            case "guid" -> "0e1f9f1a-0000-0000-0000-000000000000";
            default -> "'x'";
        };
    }

    /**
     * An expression using one advertised operator, or {@code null} for an operator this helper cannot
     * phrase - an association, or a string function against a non-string path.
     */
    private String expressionFor(FilterPropertyMetadata property, String operator) {
        if (isAssociation(property.path())) {
            return null;
        }
        String literal = sampleLiteral(property.type());
        return switch (operator) {
            case "eq", "ne", "gt", "ge", "lt", "le" -> property.path() + " " + operator + " " + literal;
            case "in" -> property.path() + " in (" + literal + ")";
            case "contains", "startswith", "endswith" ->
                    "string".equals(property.type()) ? operator + "(" + property.path() + ", 'x')" : null;
            default -> null;
        };
    }
}
