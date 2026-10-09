package ru.ludwigandreas.odatafilter.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;
import ru.ludwigandreas.odatafilter.core.ODataQueryOptions;

/**
 * The half of the one-parameter signature that keeps the fourth objection against the deleted resolver
 * from being inherited: a resolver-bound parameter is invisible to springdoc unless something describes
 * it.
 */
class ODataQueryOptionsOpenApiCustomizerTest {

    private final ODataQueryOptionsOpenApiCustomizer customizer = new ODataQueryOptionsOpenApiCustomizer();

    @SuppressWarnings("unused")
    static class Controller {
        public String search(ODataQueryOptions options) {
            return "";
        }

        public String get(String id) {
            return "";
        }
    }

    private HandlerMethod handler(String method, Class<?>... parameterTypes) throws Exception {
        return new HandlerMethod(new Controller(), Controller.class.getMethod(method, parameterTypes));
    }

    @Test
    @DisplayName("all five options are documented as query parameters with their types")
    void documentsTheFiveOptions() throws Exception {
        Operation operation = customizer.customize(new Operation(), handler("search", ODataQueryOptions.class));

        List<String> names = operation.getParameters().stream().map(Parameter::getName).toList();
        assertThat(names).containsExactlyInAnyOrder("$filter", "$orderby", "$top", "$skip", "$count");
        assertThat(operation.getParameters()).allSatisfy(parameter -> {
            assertThat(parameter.getIn()).isEqualTo("query");
            assertThat(parameter.getRequired()).isFalse();
            assertThat(parameter.getDescription()).isNotBlank();
            assertThat(parameter.getSchema()).isNotNull();
        });
    }

    @Test
    @DisplayName("$top and $skip are integers and $count a boolean, so a client need not guess")
    void documentsTheScalarTypes() throws Exception {
        Operation operation = customizer.customize(new Operation(), handler("search", ODataQueryOptions.class));

        assertThat(schemaType(operation, "$top")).isEqualTo("integer");
        assertThat(schemaType(operation, "$skip")).isEqualTo("integer");
        assertThat(schemaType(operation, "$count")).isEqualTo("boolean");
        assertThat(schemaType(operation, "$filter")).isEqualTo("string");
    }

    @Test
    @DisplayName("an operation that does not take the options is left alone")
    void ignoresUnrelatedOperations() throws Exception {
        Operation operation = customizer.customize(new Operation(), handler("get", String.class));

        assertThat(operation.getParameters()).isNull();
    }

    @Test
    @DisplayName("a parameter the application already documented is not duplicated")
    void doesNotDuplicateExistingParameters() throws Exception {
        Operation operation = new Operation().addParametersItem(
                new Parameter().name("$filter").in("query").description("the application's own wording"));

        customizer.customize(operation, handler("search", ODataQueryOptions.class));

        assertThat(operation.getParameters().stream().filter(p -> "$filter".equals(p.getName()))).hasSize(1);
        assertThat(operation.getParameters().stream()
                .filter(p -> "$filter".equals(p.getName()))
                .findFirst().orElseThrow().getDescription())
                .isEqualTo("the application's own wording");
    }

    private String schemaType(Operation operation, String name) {
        return operation.getParameters().stream()
                .filter(parameter -> name.equals(parameter.getName()))
                .findFirst().orElseThrow()
                .getSchema().getType();
    }
}
