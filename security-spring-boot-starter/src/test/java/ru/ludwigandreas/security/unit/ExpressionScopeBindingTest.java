package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.ludwigandreas.security.unit.OrderFixture.ORDER;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.security.data.DataScope;
import ru.ludwigandreas.security.data.DataScopeMapping;
import ru.ludwigandreas.security.data.DataScopePredicateFactory;
import ru.ludwigandreas.security.data.ScopeBinding;
import ru.ludwigandreas.security.data.ScopeDimension;
import ru.ludwigandreas.security.unit.OrderFixture.Order;

/**
 * The escape hatch, and the test shape its javadoc points at: because the two halves of a custom
 * binding are independent pieces of code, the thing worth testing is that they agree.
 */
class ExpressionScopeBindingTest {

    private static final ScopeDimension MENTIONED = ScopeDimension.of("mentioned");

    private final DataScopePredicateFactory factory = new DataScopePredicateFactory();

    /** "Mentioned, or the creator" - a disjunction no single path can express. */
    private DataScopeMapping<Order> mapping() {
        return DataScopeMapping.forResource("order", Order.class)
                .bindExpression(MENTIONED,
                        subjects -> ORDER.mentions.any().userId.in(subjects)
                                .or(ORDER.createdBy.in(subjects)),
                        (order, subjects) -> order.mentionedUserIds().stream().anyMatch(subjects::contains)
                                || subjects.contains(order.getCreatedBy()),
                        "mentioned on or creator of the order")
                .build();
    }

    @Test
    void compilesTheCallerSuppliedPredicate() {
        String rendered = factory.toPredicate(DataScope.restrictedTo(MENTIONED, "user-1"), mapping())
                .toString();

        assertThat(rendered)
                .contains("any(order.mentions).userId")
                .contains("order.createdBy")
                .contains("||");
    }

    /**
     * The property that matters for this shape. Every row is classified twice - once by the predicate's
     * intent and once by the check - and a disagreement is a list endpoint and a findById answering
     * differently for the same caller.
     */
    @Test
    @DisplayName("the predicate and the check classify the same rows identically")
    void bothHalvesAgreeAcrossTheRelevantCases() {
        ScopeBinding<Order> binding = mapping().binding(MENTIONED).orElseThrow();
        Set<String> allowed = Set.of("user-1");

        record Case(String description, Order order, boolean expected) {
        }
        List<Case> cases = List.of(
                new Case("mentioned", new Order("someone-else", "user-1"), true),
                new Case("creator", new Order("user-1"), true),
                new Case("creator and mentioned", new Order("user-1", "user-1"), true),
                new Case("neither", new Order("someone-else", "user-2"), false),
                new Case("no mentions, other creator", new Order("someone-else"), false));

        for (Case testCase : cases) {
            assertThat(binding.matches(testCase.order(), allowed))
                    .as("check disagrees with the predicate's intent for: %s", testCase.description())
                    .isEqualTo(testCase.expected());
        }
    }

    @Test
    @DisplayName("no allowed values means the binding matches nothing, never everything")
    void emptyValuesDeny() {
        ScopeBinding<Order> binding = mapping().binding(MENTIONED).orElseThrow();

        assertThat(binding.matches(Set.of())).isNull();
        assertThat(binding.matches(new Order("user-1"), Set.of())).isFalse();
    }

    @Test
    @DisplayName("a binding with no description could not be named in a configuration error")
    void requiresADescription() {
        assertThatThrownBy(() -> DataScopeMapping.forResource("order", Order.class)
                .bindExpression(MENTIONED, subjects -> null, (order, subjects) -> false, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
    }
}
