package ru.ludwigandreas.odatafilter.web;

import java.util.regex.Pattern;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import ru.ludwigandreas.odatafilter.core.ODataFilterService;
import ru.ludwigandreas.odatafilter.core.ODataQueryOptions;
import ru.ludwigandreas.odatafilter.exception.InvalidQueryOptionException;
import ru.ludwigandreas.odatafilter.properties.ODataFilterProperties;

/**
 * Binds a controller parameter of type {@link ODataQueryOptions} from the request's {@code $filter},
 * {@code $orderby}, {@code $top}, {@code $skip} and {@code $count} query parameters, so an endpoint
 * declares one parameter instead of five {@code @RequestParam}s.
 *
 * <h2>Why this is not the resolver that was removed</h2>
 *
 * <p>This module used to ship an {@code ODataQueryArgumentResolver} that filled in an
 * {@code ODataQuery<Employee>}. It was deprecated, off by default and is now deleted, for four
 * reasons - and the reading of the request was never one of them. That half is kept here verbatim;
 * what is gone is everything after it. Each objection and why it does not apply:
 *
 * <ul>
 *   <li><b>It named a JPA entity in a controller signature</b>, which {@code architecture-rules}'
 *       {@code web.controllers-do-not-expose-entities} rejects - it inspects type arguments, so even
 *       a DTO return type did not save it. {@link ODataQueryOptions} has no type parameter and names
 *       no entity.
 *   <li><b>What it produced was a QueryDSL {@code Predicate}</b>, which only a repository can
 *       execute, so the controller had to either hold a repository or push the entity into the
 *       service API. This resolver produces the caller's request, not a query plan, so the options
 *       travel down to the repository and the parse happens there.
 *   <li><b>springdoc could not describe it</b>, so the query options vanished from the OpenAPI
 *       document. {@code ODataQueryOptionsOpenApiCustomizer} describes this parameter, which is the
 *       half of the objection that needed new code rather than just a different type.
 *   <li><b>Argument resolution runs before the handler's {@code @PreAuthorize}</b>, so a caller who
 *       could not use the endpoint at all could still learn from a 403 which fields are filterable.
 *       This resolver evaluates no policy and resolves no roles: the only way it can fail is a
 *       malformed scalar option, which is a 400 about the request and says nothing about the data
 *       model.
 * </ul>
 *
 * <p>Parsing the options - selecting the entity's policy, checking fields and roles, building the
 * predicate - stays in {@link ODataFilterService}, called from the repository.
 */
public class ODataQueryOptionsArgumentResolver implements HandlerMethodArgumentResolver {

    /** What {@code $top} and {@code $skip} accept, tested before parsing rather than after failing. */
    private static final Pattern INTEGER = Pattern.compile("-?\\d{1,10}");

    private final ODataFilterProperties properties;

    public ODataQueryOptionsArgumentResolver(ODataFilterProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return ODataQueryOptions.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        return new ODataQueryOptions(
                param(webRequest, "$filter", "filter"),
                param(webRequest, "$orderby", "orderby"),
                parseInt(param(webRequest, "$top", "top"), "$top"),
                parseInt(param(webRequest, "$skip", "skip"), "$skip"),
                parseBoolean(param(webRequest, "$count", "count")));
    }

    /**
     * Reads the OData name first, then the non-prefixed alias - unless
     * {@code odata.filter.web.dollar-prefixed-parameters-only=true}, since many HTTP clients and API
     * gateways mangle or reject a leading {@code $} in a query parameter name.
     */
    private String param(NativeWebRequest request, String dollarName, String plainName) {
        String value = request.getParameter(dollarName);
        if (value == null && !properties.getWeb().isDollarPrefixedParametersOnly()) {
            value = request.getParameter(plainName);
        }
        return value;
    }

    /**
     * Tests the shape first rather than catching {@link NumberFormatException}.
     *
     * <p>Both spellings reject the same inputs, and this one does not catch an exception in the web
     * layer - which {@code architecture-rules}' {@code exceptions.controllers-do-not-catch-checked-exceptions}
     * refuses, because a catch there is how a parse failure turns into a 500 or into a silently
     * substituted default. The guard makes the rejection the only outcome.
     */
    private Integer parseInt(String value, String option) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (!INTEGER.matcher(trimmed).matches()) {
            throw new InvalidQueryOptionException(option, "an integer");
        }
        return Integer.valueOf(trimmed);
    }

    /**
     * Strictly {@code true} or {@code false}.
     *
     * <p>{@link Boolean#parseBoolean} is not used: it maps every other string to {@code false}, so
     * {@code $count=yes} would silently take the caller's total away instead of telling it the value
     * was not understood.
     */
    private Boolean parseBoolean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if ("true".equalsIgnoreCase(trimmed)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(trimmed)) {
            return Boolean.FALSE;
        }
        throw new InvalidQueryOptionException("$count", "true or false");
    }
}
