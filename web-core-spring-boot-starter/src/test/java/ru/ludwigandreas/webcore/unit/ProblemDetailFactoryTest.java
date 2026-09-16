package ru.ludwigandreas.webcore.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ProblemDetail;
import ru.ludwigandreas.webcore.config.WebCoreProperties;
import ru.ludwigandreas.webcore.problem.ProblemDefinition;
import ru.ludwigandreas.webcore.problem.ProblemDetailFactory;
import ru.ludwigandreas.webcore.problem.ProblemMessages;
import ru.ludwigandreas.webcore.problem.ProblemStatus;
import ru.ludwigandreas.webcore.problem.Violation;
import ru.ludwigandreas.webcore.trace.TraceIdProvider;

/** What actually goes on the wire: every member of the document, and the two privacy switches. */
class ProblemDetailFactoryTest {

    private static final String BUNDLE = "i18n/test-module-messages";

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    @DisplayName("renders every standard member plus the machine-readable code")
    void rendersStandardMembers() {
        ProblemDetail problem = factory(new WebCoreProperties.Problem())
                .create(ProblemDefinition.of(ProblemStatus.CONFLICT, "test.problem.conflict"), "/api/v1/things/7");

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getType()).hasToString("urn:ludwig:problem:test.problem.conflict");
        assertThat(problem.getTitle()).isEqualTo("Conflicting thing");
        assertThat(problem.getDetail()).isEqualTo("That thing already exists.");
        assertThat(problem.getInstance()).hasToString("/api/v1/things/7");
        assertThat(problem.getProperties()).containsEntry("code", "test.problem.conflict");
        assertThat(problem.getProperties()).containsKey("timestamp");
    }

    @Test
    @DisplayName("falls back to the HTTP reason phrase when a code has no title")
    void titleFallsBackToReasonPhrase() {
        ProblemDetail problem = factory(new WebCoreProperties.Problem())
                .create(ProblemDefinition.of(ProblemStatus.NOT_FOUND, "test.problem.untranslated"), "/x");

        assertThat(problem.getTitle()).isEqualTo("Not Found");
        // An untranslated key surfaces as the detail on purpose: a missing translation is a
        // packaging bug, and this is how it gets noticed rather than hidden behind generic prose.
        assertThat(problem.getDetail()).isEqualTo("test.problem.untranslated");
    }

    @Test
    @DisplayName("resolves title and detail in the request's locale")
    void localizesToRequestLocale() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("ru"));

        ProblemDetail problem = factory(new WebCoreProperties.Problem())
                .create(ProblemDefinition.of(ProblemStatus.CONFLICT, "test.problem.conflict"), "/x");

        assertThat(problem.getDetail()).isEqualTo("Такая вещь уже существует.");
    }

    @Test
    @DisplayName("formats the definition's arguments into the detail")
    void formatsArguments() {
        ProblemDetail problem = factory(new WebCoreProperties.Problem())
                .create(ProblemDefinition.of(ProblemStatus.INVALID, "test.problem.with-args", 42), "/x");

        assertThat(problem.getDetail()).isEqualTo("Limit is 42.");
    }

    @Test
    @DisplayName("publishes the trace id under the configured member name")
    void publishesTraceId() {
        WebCoreProperties.Problem config = new WebCoreProperties.Problem();
        config.setTraceIdProperty("correlationId");
        ProblemDetailFactory factory = new ProblemDetailFactory(
                ProblemMessages.ofBundles(BUNDLE), () -> Optional.of("abc123"), config);

        ProblemDetail problem =
                factory.create(ProblemDefinition.of(ProblemStatus.INTERNAL, "test.problem.conflict"), "/x");

        assertThat(problem.getProperties()).containsEntry("correlationId", "abc123");
    }

    @Test
    @DisplayName("omits instance, timestamp and trace id when they are switched off")
    void honoursOmissionSwitches() {
        WebCoreProperties.Problem config = new WebCoreProperties.Problem();
        config.setIncludeInstance(false);
        config.setIncludeTimestamp(false);
        config.setIncludeTraceId(false);
        ProblemDetailFactory factory = new ProblemDetailFactory(
                ProblemMessages.ofBundles(BUNDLE), () -> Optional.of("abc123"), config);

        ProblemDetail problem =
                factory.create(ProblemDefinition.of(ProblemStatus.INVALID, "test.problem.conflict"), "/x");

        assertThat(problem.getInstance()).isNull();
        assertThat(problem.getProperties()).doesNotContainKeys("timestamp", "traceId");
    }

    @Test
    @DisplayName("a definition's own properties are published as top-level members")
    void publishesDefinitionProperties() {
        ProblemDetail problem = factory(new WebCoreProperties.Problem()).create(
                ProblemDefinition.of(ProblemStatus.INVALID, "test.problem.conflict")
                        .withProperty("property", "supplierCost"),
                "/x");

        assertThat(problem.getProperties()).containsEntry("property", "supplierCost");
    }

    @Test
    @DisplayName("violations are rendered without the submitted value by default")
    void hidesRejectedValueByDefault() {
        ProblemDetail problem = factory(new WebCoreProperties.Problem())
                .create(ProblemDefinition.of(ProblemStatus.INVALID, "test.problem.conflict"), "/x");
        factory(new WebCoreProperties.Problem()).withViolations(problem,
                List.of(new Violation("password", "must not be blank", "NotBlank", "hunter2")));

        assertThat(rendered(problem))
                .containsEntry("field", "password")
                .containsEntry("message", "must not be blank")
                .containsEntry("code", "NotBlank")
                .doesNotContainKey("rejectedValue");
    }

    @Test
    @DisplayName("the submitted value is echoed only when explicitly switched on")
    void echoesRejectedValueWhenEnabled() {
        WebCoreProperties.Problem config = new WebCoreProperties.Problem();
        config.setIncludeRejectedValue(true);
        ProblemDetailFactory factory = factory(config);

        ProblemDetail problem =
                factory.create(ProblemDefinition.of(ProblemStatus.INVALID, "test.problem.conflict"), "/x");
        factory.withViolations(problem, List.of(new Violation("age", "must be positive", "Positive", -1)));

        assertThat(rendered(problem)).containsEntry("rejectedValue", -1);
    }

    @Test
    @DisplayName("violations attached by a mapper obey the same rejected-value policy")
    void appliesPolicyToMapperSuppliedViolations() {
        // A module attaching violations as a plain property must not be able to route around the
        // privacy switch - which is why the factory renders Violation wherever it appears.
        ProblemDetail problem = factory(new WebCoreProperties.Problem()).create(
                ProblemDefinition.of(ProblemStatus.INVALID, "test.problem.conflict")
                        .withProperty("violations", List.of(
                                new Violation("token", "must match", "Pattern", "secret-token"))),
                "/x");

        assertThat(rendered(problem)).doesNotContainKey("rejectedValue");
    }

    @Test
    @DisplayName("the type URI uses the configured prefix")
    void honoursTypePrefix() {
        WebCoreProperties.Problem config = new WebCoreProperties.Problem();
        config.setTypePrefix("https://errors.example.com/");
        ProblemDetail problem = factory(config)
                .create(ProblemDefinition.of(ProblemStatus.INVALID, "test.problem.conflict"), "/x");

        assertThat(problem.getType()).hasToString("https://errors.example.com/test.problem.conflict");
    }

    private ProblemDetailFactory factory(WebCoreProperties.Problem config) {
        return new ProblemDetailFactory(ProblemMessages.ofBundles(BUNDLE), TraceIdProvider.none(), config);
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> rendered(ProblemDetail problem) {
        List<java.util.Map<String, Object>> violations =
                (List<java.util.Map<String, Object>>) problem.getProperties().get("violations");
        return violations.get(0);
    }
}
