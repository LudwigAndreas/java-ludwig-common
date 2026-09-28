package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.StringPath;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.security.data.DataScope;
import ru.ludwigandreas.security.data.DataScopeMapping;
import ru.ludwigandreas.security.data.DataScopePredicateFactory;
import ru.ludwigandreas.security.data.ScopeDimension;

class DataScopePredicateFactoryTest {

    private record Order(String createdBy, UUID partnerId) {
    }

    private static final StringPath CREATED_BY = Expressions.stringPath("createdBy");
    private static final com.querydsl.core.types.dsl.ComparablePath<UUID> PARTNER_ID =
            Expressions.comparablePath(UUID.class, "partnerId");

    private final DataScopePredicateFactory factory = new DataScopePredicateFactory();

    private DataScopeMapping<Order> mapping() {
        return DataScopeMapping.forResource("order", Order.class)
                .owner(CREATED_BY, Order::createdBy)
                .partner(PARTNER_ID, Order::partnerId, UUID::fromString)
                .build();
    }

    @Test
    @DisplayName("an unrestricted scope adds a constant, never a filter")
    void unrestrictedScopeDoesNotFilter() {
        BooleanExpression predicate = factory.toPredicate(DataScope.all(), mapping());

        assertThat(predicate.toString()).doesNotContain("createdBy");
    }

    @Test
    @DisplayName("a denial compiles to a predicate that matches nothing, not to an absent filter")
    void denialCompilesToNeverMatches() {
        BooleanExpression predicate = factory.toPredicate(DataScope.none(), mapping());

        assertThat(predicate.toString()).isEqualTo("true = false");
    }

    @Test
    void ownerScopeBecomesAnInPredicateOnTheOwnerColumn() {
        BooleanExpression predicate = factory.toPredicate(
                DataScope.restrictedTo(ScopeDimension.OWNER, "user-1"), mapping());

        assertThat(predicate.toString()).isEqualTo("createdBy = user-1");
    }

    @Test
    @DisplayName("dimensions inside one grant are ANDed, separate grants are ORed")
    void conjunctionWithinAGrantAndDisjunctionAcross() {
        UUID partner = UUID.randomUUID();
        DataScope ownAndPartner = DataScope.restrictedTo(Map.of(
                ScopeDimension.OWNER, Set.of("user-1"),
                ScopeDimension.PARTNER, Set.of(partner.toString())));
        DataScope union = ownAndPartner.union(DataScope.restrictedTo(ScopeDimension.OWNER, "user-2"));

        String rendered = factory.toPredicate(union, mapping()).toString();

        assertThat(rendered).contains("&&").contains("||");
    }

    @Test
    @DisplayName("a grant value the column cannot hold denies rather than widening")
    void unparseableValueDenies() {
        DataScope scope = DataScope.restrictedTo(ScopeDimension.PARTNER, "not-a-uuid");

        assertThat(factory.toPredicate(scope, mapping()).toString()).isEqualTo("true = false");
    }
}
