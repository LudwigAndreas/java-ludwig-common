package ru.ludwigandreas.odatafilter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import ru.ludwigandreas.odatafilter.core.ODataQueryOptions;
import ru.ludwigandreas.odatafilter.exception.InvalidQueryOptionException;
import ru.ludwigandreas.odatafilter.properties.ODataFilterProperties;

/** Reading the request is all this resolver does; everything it must not do is in its javadoc. */
class ODataQueryOptionsArgumentResolverTest {

    private final ODataFilterProperties properties = new ODataFilterProperties();
    private final ODataQueryOptionsArgumentResolver resolver =
            new ODataQueryOptionsArgumentResolver(properties);

    private ODataQueryOptions resolve(MockHttpServletRequest request) {
        return (ODataQueryOptions) resolver.resolveArgument(
                null, null, new ServletWebRequest(request), null);
    }

    @Test
    @DisplayName("the five dollar-prefixed options are read")
    void readsTheDollarPrefixedOptions() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("$filter", "name eq 'a'");
        request.setParameter("$orderby", "name desc");
        request.setParameter("$top", "20");
        request.setParameter("$skip", "25");
        request.setParameter("$count", "false");

        ODataQueryOptions options = resolve(request);

        assertThat(options.filter()).isEqualTo("name eq 'a'");
        assertThat(options.orderBy()).isEqualTo("name desc");
        assertThat(options.top()).isEqualTo(20);
        assertThat(options.skip()).isEqualTo(25);
        assertThat(options.countRequested()).isFalse();
    }

    @Test
    @DisplayName("the non-prefixed aliases are read too, because gateways mangle a leading $")
    void readsThePlainAliases() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("filter", "name eq 'a'");
        request.setParameter("top", "5");

        ODataQueryOptions options = resolve(request);

        assertThat(options.filter()).isEqualTo("name eq 'a'");
        assertThat(options.top()).isEqualTo(5);
    }

    @Test
    @DisplayName("the aliases can be switched off, and then only the OData names are read")
    void aliasesCanBeSwitchedOff() {
        properties.getWeb().setDollarPrefixedParametersOnly(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("filter", "name eq 'a'");

        assertThat(resolve(request).filter()).isNull();
    }

    @Test
    @DisplayName("an absent option is absent, not a default decided here")
    void absentOptionsStayAbsent() {
        ODataQueryOptions options = resolve(new MockHttpServletRequest());

        assertThat(options.filter()).isNull();
        assertThat(options.top()).isNull();
        assertThat(options.skip()).isNull();
        assertThat(options.countRequested()).isTrue();
    }

    @Test
    @DisplayName("$count=yes is refused rather than silently read as false")
    void refusesANonBooleanCount() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("$count", "yes");

        // Boolean.parseBoolean would map this to false and take the caller's total away without
        // telling it the value was not understood.
        assertThatThrownBy(() -> resolve(request))
                .isInstanceOf(InvalidQueryOptionException.class)
                .hasMessageContaining("$count")
                .hasMessageNotContaining("yes");
    }

    @Test
    @DisplayName("$top=abc names $top, not the filter expression")
    void refusesANonIntegerTop() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("$top", "abc");

        assertThatThrownBy(() -> resolve(request))
                .isInstanceOf(InvalidQueryOptionException.class)
                .hasMessageContaining("$top");
    }

    @Test
    @DisplayName("the offending value never reaches the message, because the message reaches the logs")
    void keepsTheCallersValueOutOfTheMessage() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("$skip", "not-a-number");

        assertThatThrownBy(() -> resolve(request))
                .hasMessageNotContaining("not-a-number");
    }

    @Test
    @DisplayName("true and false are accepted in any case")
    void acceptsEitherCase() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("$count", "FALSE");

        assertThat(resolve(request).countRequested()).isFalse();
    }
}
