package ru.ludwigandreas.odatafilter.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.querydsl.core.types.dsl.PathBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import ru.ludwigandreas.odatafilter.querydsl.ODataPaths;
import ru.ludwigandreas.odatafilter.testmodel.Employee;

/**
 * The executor's two decisions that need no database: whether it refuses a mismatched root, and what it
 * reports when the caller declined the count. The query it builds is exercised against a real Postgres
 * by {@code ODataFilterIntegrationTest}, because a query's correctness is not something a mock can
 * assert - a mock would agree with whatever the executor did.
 */
class ODataQueryExecutorTest {

    @Test
    @DisplayName("a root aliased differently from the predicate's is refused before any query is built")
    void refusesAMismatchedRoot() {
        assertThatThrownBy(() ->
                ODataPaths.requireMatchingAlias(Employee.class, new PathBuilder<>(Employee.class, "e")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cross-join");
    }

    @Test
    @DisplayName("a page carries the offset it was given, not a page number derived from it")
    void pageCarriesTheAbsoluteOffset() {
        ODataPage<String> page = new ODataPage<>(java.util.List.of("a", "b"), 25, 20, 100L);

        assertThat(page.offset()).isEqualTo(25);
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.hasTotal()).isTrue();
    }

    @Test
    @DisplayName("a declined count is an absent total, which is why this is not a Spring Data Page")
    void declinedCountLeavesNoTotal() {
        ODataPage<String> page = new ODataPage<>(java.util.List.of("a"), 0, 20, null);

        assertThat(page.hasTotal()).isFalse();
        assertThat(page.totalElements()).isNull();
    }

    @Test
    @DisplayName("mapping a page keeps its paging members, so no entity has to travel to map them")
    void mappingKeepsPaging() {
        ODataPage<Integer> mapped = new ODataPage<>(java.util.List.of("a", "bb"), 40, 20, 99L)
                .map(String::length);

        assertThat(mapped.content()).containsExactly(1, 2);
        assertThat(mapped.offset()).isEqualTo(40);
        assertThat(mapped.size()).isEqualTo(20);
        assertThat(mapped.totalElements()).isEqualTo(99L);
    }

    @Test
    @DisplayName("a page's content list is copied, so the result cannot be changed after the fact")
    void copiesContent() {
        java.util.List<String> mutable = new java.util.ArrayList<>(java.util.List.of("a"));
        ODataPage<String> page = new ODataPage<>(mutable, 0, 20, 1L);

        mutable.add("b");

        assertThat(page.content()).containsExactly("a");
    }

    @Test
    @DisplayName("the ordering handed to the query is the resolved sort, tie-breaker included")
    void orderingComesFromTheResolvedSort() {
        // The sort an ODataQuery carries always ends on the entity's defaultOrderBy, so there is no
        // unordered case for the executor to fall back on - which is what keeps $skip paging stable.
        Sort resolved = Sort.by(Sort.Order.desc("name"), Sort.Order.asc("id"));

        assertThat(ODataPaths.orderSpecifiers(Employee.class, resolved)).hasSize(2);
    }
}
