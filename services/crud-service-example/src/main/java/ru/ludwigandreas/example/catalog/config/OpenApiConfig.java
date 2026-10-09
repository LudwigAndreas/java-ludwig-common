package ru.ludwigandreas.example.catalog.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.Map;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The published API description.
 *
 * <p>The one thing worth doing by hand here is the {@code ProblemDetail} schema. springdoc infers a
 * response schema from a controller's return type, and every error this service produces is rendered
 * by {@code web-core}'s advice rather than returned from a controller - so without this, the generated
 * document describes only the happy paths and a client generator produces code that cannot read an
 * error. Declaring it once, as a shared component, is what makes the error contract part of the API
 * rather than folklore.
 *
 * <p>The {@code code} member is the part a client should branch on: it is stable, whereas
 * {@code detail} is localized and will read differently depending on the caller's
 * {@code Accept-Language}.
 *
 * <p>Deliberately the same shape as {@code notification-service}'s. This service is the reference a
 * new one is copied from, so the thing being demonstrated is that a service declares its own
 * {@code info} block and reuses the platform's one error contract - not that the description is
 * inherited from somewhere, which would hide the decision.
 *
 * <p>The five OData query options on the product search endpoint are <em>not</em> declared here.
 * They are contributed by {@code odata-filter-spring-boot-starter}'s
 * {@code ODataQueryOptionsOpenApiCustomizer}, which this service activates simply by having springdoc
 * on the classpath. Writing them again here is how the document and the parser start to disagree.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    private static final String PROBLEM_SCHEMA = "ProblemDetail";

    @Bean
    public OpenAPI catalogOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Product catalog")
                        .version("v1")
                        .description("""
                                Create, read, update and delete products, and search them with the
                                OData query options. Which rows a caller sees is decided by the
                                scoped query rather than by the endpoint, so two callers may get
                                different results from the same request.

                                Errors are RFC 9457 problem documents. Branch on `code`, which is
                                stable; `detail` is localized from the caller's Accept-Language and
                                is written for a human.
                                """))
                .components(new Components().addSchemas(PROBLEM_SCHEMA, problemSchema()));
    }

    /**
     * Attaches the problem document to every operation as its {@code default} response.
     *
     * <p>Without this the schema above is declared and then dropped. springdoc prunes a component
     * nothing refers to, so a hand-added schema only survives if some operation or DTO happens to
     * reference it - which in {@code notification-service} one DTO does, by having a
     * {@code ProblemDetail} field. Relying on that coincidence means the error contract disappears
     * from the document the moment that unrelated field is removed.
     *
     * <p>{@code default} is the accurate status to use rather than guessing at 400 and 404 per
     * operation: {@code web-core}'s single advice can answer on any operation with any of the
     * statuses its mappers produce, so "every other response is a problem document" is exactly what
     * is true here. Declaring 400/404/409 individually would be a list that drifts from the mappers.
     *
     * <p>An operation that already declares a {@code default} response keeps it; this fills a gap
     * rather than overriding a deliberate choice.
     */
    @Bean
    public OperationCustomizer problemResponseCustomizer() {
        return (operation, handlerMethod) -> {
            ApiResponses responses =
                    operation.getResponses() == null ? new ApiResponses() : operation.getResponses();
            if (responses.get("default") == null) {
                responses.addApiResponse("default", new ApiResponse()
                        .description("RFC 9457 problem document. Branch on `code`, never on `detail`.")
                        .content(new Content().addMediaType("application/problem+json",
                                new MediaType().schema(
                                        new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA)))));
            }
            operation.setResponses(responses);
            return operation;
        };
    }

    private Schema<?> problemSchema() {
        Schema<Object> schema = new Schema<>();
        schema.setType("object");
        schema.setDescription("RFC 9457 problem document, as produced by web-core-spring-boot-starter.");
        schema.setProperties(Map.of(
                "type", new StringSchema().description("URI identifying the problem type."),
                "title", new StringSchema().description("Short, localized summary."),
                "status", new Schema<Integer>().type("integer").description("HTTP status code."),
                "detail", new StringSchema().description("Localized explanation, for a human."),
                "instance", new StringSchema().description("URI of the request that failed."),
                "code", new StringSchema().description(
                        "Stable machine-readable code. Branch on this, never on detail."),
                "traceId", new StringSchema().description("Trace id, for correlating with the logs.")));
        return schema;
    }
}
