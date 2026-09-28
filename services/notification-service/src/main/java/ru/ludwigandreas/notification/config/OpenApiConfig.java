package ru.ludwigandreas.notification.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import java.util.Map;
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
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    private static final String PROBLEM_SCHEMA = "ProblemDetail";

    @Bean
    public OpenAPI notificationOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Notification service")
                        .version("v1")
                        .description("""
                                Submit notification requests, inspect delivery history, and manage
                                recipient contact records, preferences and the suppression list.

                                Errors are RFC 9457 problem documents. Branch on `code`, which is
                                stable; `detail` is localized from the caller's Accept-Language and
                                is written for a human.
                                """))
                .components(new Components().addSchemas(PROBLEM_SCHEMA, problemSchema()));
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
