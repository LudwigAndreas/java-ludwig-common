package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.ludwigandreas.security.unit.OrderFixture.ORDER;

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
 * "A user may see every order they are mentioned on" - membership rather than ownership, which is the
 * case a single-valued binding cannot express on the load-time side.
 */
class CollectionScopeBindingTest {

    private static final ScopeDimension MENTIONED = ScopeDimension.of("mentioned");

    private final DataScopePredicateFactory factory = new DataScopePredicateFactory();

    private DataScopeMapping<Order> mapping() {
        return DataScopeMapping.forResource("order", Order.class)
                .owner(ORDER.createdBy, Order::getCreatedBy)
                .bindCollection(MENTIONED, ORDER.mentions.any().userId, Order::mentionedUserIds)
                .build();
    }

    @Test
    @DisplayName("the query side traverses the association, so paging and counts stay correct")
    void compilesToASubqueryOverTheAssociation() {
        DataScope scope = DataScope.restrictedTo(MENTIONED, "user-1");

        String rendered = factory.toPredicate(scope, mapping()).toString();

        // any() is QueryDSL's to-many traversal; JPA renders it as a correlated EXISTS rather than a
        // join, which is what keeps one order with four mentions from becoming four rows in a page.
        assertThat(rendered).isEqualTo("any(order.mentions).userId = user-1");
    }

    @Test
    @DisplayName("the load side matches when any related value is allowed, not only when all are")
    void postCheckIsIntersectionNotEquality() {
        ScopeBinding<Order> binding = mapping().binding(MENTIONED).orElseThrow();
        Order order = new Order("someone-else", "user-1", "user-9");

        assertThat(binding.matches(order, Set.of("user-1"))).isTrue();
        assertThat(binding.matches(order, Set.of("user-9", "user-42"))).isTrue();
        assertThat(binding.matches(order, Set.of("user-42"))).isFalse();
    }

    @Test
    @DisplayName("a row related to nothing matches nothing - the empty collection is not a wildcard")
    void emptyAssociationDenies() {
        ScopeBinding<Order> binding = mapping().binding(MENTIONED).orElseThrow();

        assertThat(binding.matches(new Order("someone-else"), Set.of("user-1"))).isFalse();
        assertThat(binding.matches(null, Set.of("user-1"))).isFalse();
    }

    @Test
    @DisplayName("both halves agree: what the query would return is what the check accepts")
    void queryAndCheckAgree() {
        DataScopeMapping<Order> mapping = mapping();
        ScopeBinding<Order> binding = mapping.binding(MENTIONED).orElseThrow();
        Set<String> allowed = Set.of("user-1");

        Order mentioned = new Order("someone-else", "user-1");
        Order notMentioned = new Order("someone-else", "user-2");

        // The query predicate keeps rows whose mentions contain user-1; the check has to say the same
        // thing about the same rows, or a list endpoint and a findById answer differently.
        assertThat(binding.matches(mentioned, allowed)).isTrue();
        assertThat(binding.matches(notMentioned, allowed)).isFalse();
        assertThat(factory.toPredicate(DataScope.restrictedTo(MENTIONED, "user-1"), mapping).toString())
                .contains("any(order.mentions).userId");
    }

    @Test
    @DisplayName("a dimension bound twice would make the enforced rule depend on builder call order")
    void rejectsADuplicateBinding() {
        DataScopeMapping.Builder<Order> builder = DataScopeMapping.forResource("order", Order.class)
                .bindCollection(MENTIONED, ORDER.mentions.any().userId, Order::mentionedUserIds);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> builder.owner(ORDER.createdBy, Order::getCreatedBy)
                                .bind(MENTIONED, ORDER.createdBy, Order::getCreatedBy))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bound twice");
    }
}
