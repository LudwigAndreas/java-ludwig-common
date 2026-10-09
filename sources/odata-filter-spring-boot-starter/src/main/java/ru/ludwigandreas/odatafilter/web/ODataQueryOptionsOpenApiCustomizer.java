package ru.ludwigandreas.odatafilter.web;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.util.ArrayList;
import java.util.List;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.web.method.HandlerMethod;
import ru.ludwigandreas.odatafilter.core.ODataQueryOptions;

/**
 * Documents the five OData query options on every operation that takes an {@link ODataQueryOptions}.
 *
 * <h2>Why this class has to exist</h2>
 *
 * <p>Taking the options as one parameter is the point of {@link ODataQueryOptions}, but the binding has
 * to go through {@link ODataQueryOptionsArgumentResolver}, because Spring's {@code @ModelAttribute}
 * cannot map a request parameter named {@code $filter} onto a record component named {@code filter} -
 * there is no aliasing in {@code WebDataBinder}. And a parameter filled in by a custom resolver is
 * invisible to springdoc, which is precisely the third of the four objections that made the deleted
 * {@code ODataQueryArgumentResolver} unusable. Writing the one-parameter signature without this
 * customizer would reproduce that defect rather than fix it.
 *
 * <p>So the pair is the whole feature: the resolver binds, this describes, and a client reading the
 * OpenAPI document sees the same five parameters it would have seen from five {@code @RequestParam}
 * declarations.
 *
 * <p>Optional dependency, like every other integration in this module: without springdoc on the
 * classpath nothing here loads and the resolver still works. The documentation is then missing, which
 * is a documentation problem and not a runtime one.
 */
public class ODataQueryOptionsOpenApiCustomizer implements OperationCustomizer {

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        boolean takesOptions = List.of(handlerMethod.getMethodParameters()).stream()
                .anyMatch(parameter -> ODataQueryOptions.class.equals(parameter.getParameterType()));
        if (!takesOptions) {
            return operation;
        }
        List<Parameter> parameters =
                operation.getParameters() == null ? new ArrayList<>() : new ArrayList<>(operation.getParameters());
        add(parameters, "$filter", new StringSchema(),
                "OData filter expression, e.g. price lt 100 and category/code eq 'TOOLS'. "
                        + "Which properties may appear is decided by the entity's policy.");
        add(parameters, "$orderby", new StringSchema(),
                "Ordering clause, e.g. name desc, createdAt asc. The server appends its own "
                        + "tie-breaker so that paging stays deterministic.");
        add(parameters, "$top", new IntegerSchema(), "Page size. Capped by the endpoint's maximum.");
        add(parameters, "$skip", new IntegerSchema(),
                "Absolute row offset. Need not be a multiple of $top; the response reports the offset "
                        + "that was applied.");
        add(parameters, "$count", new BooleanSchema(),
                "Whether to compute the total. Defaults to true; pass false to skip the count query, "
                        + "and the response then carries no total.");
        operation.setParameters(parameters);
        return operation;
    }

    private void add(List<Parameter> parameters, String name, Schema<?> schema, String description) {
        boolean alreadyDocumented = parameters.stream().anyMatch(parameter -> name.equals(parameter.getName()));
        if (alreadyDocumented) {
            return;
        }
        parameters.add(new Parameter()
                .name(name)
                .in("query")
                .required(false)
                .description(description)
                .schema(schema));
    }
}
