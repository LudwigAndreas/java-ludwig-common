package ru.ludwigandreas.odatafilter.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two defaults a request object is allowed to decide, and the one it is not.
 *
 * <p>{@code $count} resolves here because its default is a property of the protocol - silence means a
 * total. {@code $top} does not, because its default is the entity's {@code defaultPageSize}, which only
 * the policy knows; resolving it here would make "the caller asked for 20" and "the caller asked for
 * nothing" the same value.
 */
class ODataQueryOptionsTest {

    @Test
    @DisplayName("silence about $count means the caller gets a total")
    void countDefaultsToRequested() {
        assertThat(ODataQueryOptions.none().countRequested()).isTrue();
        assertThat(ODataQueryOptions.of(null, null, 20, 0).countRequested()).isTrue();
    }

    @Test
    @DisplayName("only an explicit $count=false takes the total away")
    void explicitFalseDeclinesTheCount() {
        assertThat(new ODataQueryOptions(null, null, null, null, Boolean.FALSE).countRequested()).isFalse();
        assertThat(new ODataQueryOptions(null, null, null, null, Boolean.TRUE).countRequested()).isTrue();
    }

    @Test
    @DisplayName("top and skip stay absent rather than being defaulted here")
    void pagingIsNotDefaulted() {
        ODataQueryOptions options = ODataQueryOptions.none();

        assertThat(options.top()).isNull();
        assertThat(options.skip()).isNull();
    }

    @Test
    @DisplayName("a blank filter is no filter, so a caller sending $filter= gets everything")
    void blankFilterIsNoFilter() {
        assertThat(ODataQueryOptions.filterOnly(null).hasFilter()).isFalse();
        assertThat(ODataQueryOptions.filterOnly("   ").hasFilter()).isFalse();
        assertThat(ODataQueryOptions.filterOnly("name eq 'a'").hasFilter()).isTrue();
    }

    @Test
    @DisplayName("filterOnly carries no paging, which is what a report needs")
    void filterOnlyCarriesNoPaging() {
        ODataQueryOptions options = ODataQueryOptions.filterOnly("name eq 'a'");

        assertThat(options.top()).isNull();
        assertThat(options.skip()).isNull();
        assertThat(options.orderBy()).isNull();
    }
}
