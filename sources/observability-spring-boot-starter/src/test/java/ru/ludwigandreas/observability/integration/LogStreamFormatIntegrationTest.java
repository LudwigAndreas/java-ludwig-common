package ru.ludwigandreas.observability.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.observability.config.ObservabilityCoreAutoConfiguration;

/**
 * The selected format is in effect for the first line a service writes to standard output, and for
 * every line after it.
 *
 * <p>The other logging tests look at which encoder is attached. This one reads what actually came
 * out of the process, because the requirement is about the stream: a service that writes text for
 * its first lines and JSON thereafter has the right encoder attached and still loses its startup and
 * bootstrap lines in most shippers - which are the lines needed when it fails to start. So nothing
 * here switches the banner off or filters a line out before asserting; whatever a service with no
 * logging configuration writes is what is checked. That is how this test found that the banner is
 * printed around the logging system, and why the module now defaults it off under JSON.
 *
 * <p>Named {@code ...IntegrationTest} to match the platform convention. This module does not bind
 * failsafe, so surefire runs it at {@code test} like the rest of the module's tests.
 */
@ExtendWith(OutputCaptureExtension.class)
class LogStreamFormatIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void writesEveryLineAsAStructuredRecordFromTheFirst(CapturedOutput output) throws Exception {
        try (ConfigurableApplicationContext context = run(Application.class)) {
            assertThat(context.isRunning()).isTrue();
        }

        List<JsonNode> records = structuredRecords(output.getOut());
        assertThat(records).isNotEmpty();
        assertThat(records).allSatisfy(record -> {
            assertThat(record.path("service.name").asText()).isEqualTo("catalog");
            assertThat(record.path("service.commit.id").asText()).isEqualTo("c1fc5b8");
        });
    }

    @Test
    void emitsTheStartupIdentityEventOnceAsAStructuredRecord(CapturedOutput output) throws Exception {
        try (ConfigurableApplicationContext context = run(Application.class)) {
            assertThat(context.isRunning()).isTrue();
        }

        List<JsonNode> identity = structuredRecords(output.getOut()).stream()
                .filter(record -> record.path("message").asText().startsWith("Application identity:"))
                .toList();
        assertThat(identity).hasSize(1);
        assertThat(identity.get(0).path("build.branch").asText()).isEqualTo("release/1.4");
        assertThat(identity.get(0).path("build.dirty").asText()).isEqualTo("true");
        assertThat(identity.get(0).path("message").asText()).contains("tree=dirty");
    }

    @Test
    void writesABootstrapFailureAsStructuredRecordsToo(CapturedOutput output) throws Exception {
        assertThatThrownBy(() -> run(FailingApplication.class)).hasRootCauseMessage("no database today");

        List<JsonNode> records = structuredRecords(output.getOut());
        assertThat(records)
                .filteredOn(record -> record.path("log.level").asText().equals("ERROR"))
                .isNotEmpty()
                .anySatisfy(record ->
                        assertThat(record.path("error.stack_trace").asText()).contains("no database today"));
    }

    @Test
    void writesHumanReadableLinesFromTheFirstUnderTheLocalProfile(CapturedOutput output) {
        try (ConfigurableApplicationContext context = run(Application.class, "--spring.profiles.active=local")) {
            assertThat(context.isRunning()).isTrue();
        }

        List<String> lines = lines(output.getOut());
        assertThat(lines).isNotEmpty();
        assertThat(lines).noneMatch(line -> line.startsWith("{"));
        // Every logged line carries the identity and the commit - including the first one Spring
        // Boot logs, "Starting ...", which is written before any bean of this module exists.
        List<String> logged = lines.stream().filter(line -> line.contains(" INFO ")).toList();
        assertThat(logged).isNotEmpty()
                .allSatisfy(line -> assertThat(line).contains("[catalog/1.4.2@c1fc5b8 env=local"));
        assertThat(logged.get(0)).contains(": Starting ");
        // And the startup identity event is there, readable as text.
        assertThat(lines)
                .filteredOn(line -> line.contains("Application identity:"))
                .singleElement()
                .satisfies(line -> assertThat(line).contains("service=catalog", "branch=release/1.4", "tree=dirty"));
    }

    private ConfigurableApplicationContext run(Class<?> application, String... extraArgs) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.application.name=catalog",
                // Pinned: under surefire Spring deduces the forked booter as the main class and
                // would otherwise report surefire's own manifest version as the service's.
                "--ludwig.observability.service.version=1.4.2",
                "--ludwig.observability.build.abbreviated-commit-id=c1fc5b8",
                "--ludwig.observability.build.branch=release/1.4",
                "--ludwig.observability.build.dirty=true"));
        args.addAll(List.of(extraArgs));
        return new SpringApplicationBuilder(application)
                .web(WebApplicationType.NONE)
                .run(args.toArray(String[]::new));
    }

    /** Every non-blank line of standard output, parsed; a line that is not a JSON object fails here. */
    private List<JsonNode> structuredRecords(String out) throws Exception {
        List<JsonNode> records = new ArrayList<>();
        for (String line : lines(out)) {
            assertThat(line).as("a line of standard output under the structured format").startsWith("{");
            JsonNode record = MAPPER.readTree(line);
            assertThat(record.isObject()).isTrue();
            records.add(record);
        }
        return records;
    }

    private List<String> lines(String out) {
        return out.lines().filter(line -> !line.isBlank()).toList();
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(ObservabilityCoreAutoConfiguration.class)
    static class Application {
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(ObservabilityCoreAutoConfiguration.class)
    static class FailingApplication {

        @Bean
        Object database() {
            throw new IllegalStateException("no database today");
        }
    }
}
