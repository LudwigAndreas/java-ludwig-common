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
 * the request's {@code $filter}/{@code $top}/{@code $skip}/{@code $orderby}/{@code $count} query
 * parameters, using {@code Employee} (read off the parameter's generic signature) to select that
 * entity's policy. Reads the non-{@code $}-prefixed aliases too unless
 * {@code odata.filter.web.dollar-prefixed-parameters-only=true}, since many HTTP clients and API
 * gateways mangle or reject leading {@code $} in query parameter names.
 */
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
        Boolean count = parseBoolean(param(webRequest, "$count", "count"));

        return filterService.parse(entityType, filter, top, skip, orderBy, count);
    }

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

    private Boolean parseBoolean(String value) {
        return value == null || value.isBlank() ? null : Boolean.parseBoolean(value.trim());
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
