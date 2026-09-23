package ru.ludwigandreas.odatafilter.web;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import ru.ludwigandreas.odatafilter.config.ODataFilterProperties;
import ru.ludwigandreas.odatafilter.core.ODataFilterService;
import ru.ludwigandreas.odatafilter.core.ODataQuery;
import ru.ludwigandreas.odatafilter.exception.FilterSyntaxException;

/**
 * Resolves a controller method parameter declared as {@code ODataQuery<Employee>} straight from
 * the request's {@code $filter}/{@code $top}/{@code $skip}/{@code $orderby} query parameters,
 * using {@code Employee} (read off the parameter's generic signature) to select that entity's
 * policy.
 *
 * @deprecated it cannot be used without breaking the layering this platform enforces elsewhere.
 *     {@code ODataQuery<Employee>} names a JPA entity in a controller signature, which
 *     {@code web.controllers-do-not-expose-entities} rejects (the rule inspects type arguments), and
 *     the resulting QueryDSL {@code Predicate} is a persistence artifact produced in the web layer,
 *     which only the repository can execute - so the controller must either hold a repository
 *     itself, breaking {@code layering.controllers-do-not-access-persistence} and running the query
 *     outside any transaction, or hand the predicate down and make the entity part of the service
 *     API. Two further costs come with it: springdoc cannot describe the parameter, so
 *     {@code $filter} and friends vanish from the OpenAPI document, and argument resolution runs
 *     before the handler's {@code @PreAuthorize}, so a caller with no access to the endpoint can
 *     still learn from a 403 which fields are filterable.
 *
 *     <p>Take the options as {@code @RequestParam} strings instead, pass them down unparsed, and
 *     call {@link ODataFilterService#parse} in the repository, which is the layer that owns the
 *     entity. Disabled by default; set {@code odata.filter.web.argument-resolver-enabled=true} to
 *     keep using it.
 */
@Deprecated(since = "1.1.0")
public class ODataQueryArgumentResolver implements HandlerMethodArgumentResolver {

    private final ODataFilterService filterService;
    private final ODataFilterProperties properties;

    public ODataQueryArgumentResolver(ODataFilterService filterService, ODataFilterProperties properties) {
        this.filterService = filterService;
        this.properties = properties;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return ODataQuery.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        Class<?> entityType = resolveEntityType(parameter);

        String filter = param(webRequest, "$filter", "filter");
        String orderBy = param(webRequest, "$orderby", "orderby");
        Integer top = parseInt(param(webRequest, "$top", "top"), "$top");
        Integer skip = parseInt(param(webRequest, "$skip", "skip"), "$skip");

        return filterService.parse(entityType, filter, top, skip, orderBy);
    }

    /**
     * Reads the non-{@code $}-prefixed aliases too unless
     * {@code odata.filter.web.dollar-prefixed-parameters-only=true}, since many HTTP clients and
     * API gateways mangle or reject leading {@code $} in query parameter names.
     */
    private String param(NativeWebRequest request, String dollarName, String plainName) {
        String value = request.getParameter(dollarName);
        if (value != null || properties.getWeb().isDollarPrefixedParametersOnly()) {
            return value;
        }
        return request.getParameter(plainName);
    }

    private Integer parseInt(String value, String paramName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new FilterSyntaxException(paramName + " must be an integer, got '" + value + "'", e);
        }
    }

    private Class<?> resolveEntityType(MethodParameter parameter) {
        Type generic = parameter.getGenericParameterType();
        if (generic instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments()[0] instanceof Class<?> entityType) {
            return entityType;
        }
        throw new IllegalStateException(
                "ODataQuery<T> controller parameters must be declared with a concrete entity type, "
                        + "e.g. ODataQuery<Employee> - found: " + generic);
    }
}
