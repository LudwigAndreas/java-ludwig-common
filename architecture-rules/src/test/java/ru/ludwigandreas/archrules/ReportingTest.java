package ru.ludwigandreas.archrules;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ru.ludwigandreas.archrules.report.ArchitectureReport;
import ru.ludwigandreas.archrules.report.ArchitectureReports;
import ru.ludwigandreas.archrules.report.ArchitectureRuleRunner;
import ru.ludwigandreas.archrules.report.ColorMode;
import ru.ludwigandreas.archrules.report.ConsoleReportWriter;
import ru.ludwigandreas.archrules.report.JsonReportWriter;
import ru.ludwigandreas.archrules.report.ReportingConfiguration;
import ru.ludwigandreas.archrules.report.RuleReport;
import ru.ludwigandreas.archrules.rules.DtoImmutabilityRules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two reports a run emits: one for a person reading a build log, one for everything else.
 *
 * <p>The JSON is parsed back with a real parser rather than asserted on as text - a report an
 * aggregator cannot parse is worse than no report.
 */
class ReportingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ArchitectureReport reportOfMutableDtoFixture() {
        return ArchitectureRuleRunner.run(ArchitectureRules.suite(
                Fixtures.configurationFor("bad.dtos").build()));
    }

    @Test
    @DisplayName("the JSON report is well-formed and carries identity, summary and every rule")
    void jsonReportIsStructured() throws Exception {
        JsonNode json = JSON.readTree(JsonReportWriter.toJson(reportOfMutableDtoFixture()));

        assertThat(json.get("schemaVersion").asText()).isEqualTo(ArchitectureReport.SCHEMA_VERSION);
        assertThat(json.get("tool").get("name").asText()).contains("architecture-rules");
        assertThat(json.get("service").get("name").asText()).isEqualTo("bad.dtos");
        assertThat(json.get("service").get("basePackages")).isNotEmpty();
        assertThat(json.get("generatedAt").asText()).isNotBlank();
        assertThat(json.get("summary").get("rules").asInt()).isPositive();
        assertThat(json.get("summary").get("failed").asInt()).isPositive();
        assertThat(json.get("rules")).isNotEmpty();
    }

    @Test
    @DisplayName("a violation in the JSON says what broke, where, and how to fix it")
    void jsonViolationsAreActionable() throws Exception {
        JsonNode json = JSON.readTree(JsonReportWriter.toJson(reportOfMutableDtoFixture()));

        JsonNode rule = ruleNode(json, DtoImmutabilityRules.NO_SETTERS_ON_API_MODELS.value());
        assertThat(rule.get("status").asText()).isEqualTo("violated");
        assertThat(rule.get("severity").asText()).isEqualTo("error");
        assertThat(rule.get("group").asText()).isEqualTo(RuleGroup.DTO_IMMUTABILITY.id());
        assertThat(rule.get("remediation").asText()).contains("immutable");

        JsonNode violation = rule.get("violations").get(0);
        assertThat(violation.get("message").asText()).contains("setCode");
        assertThat(violation.get("class").asText()).endsWith("MutableProductDto");
        assertThat(violation.get("sourceFile").asText()).isEqualTo("MutableProductDto.java");
        assertThat(violation.get("line").asInt()).isPositive();
        assertThat(violation.get("location").asText()).startsWith("MutableProductDto.java:");
    }

    @Test
    @DisplayName("rules that passed are reported too, so a dashboard can tell 'clean' from 'not checked'")
    void passedRulesAreIncluded() throws Exception {
        JsonNode json = JSON.readTree(JsonReportWriter.toJson(reportOfMutableDtoFixture()));

        assertThat(json.get("rules")).anySatisfy(rule ->
                assertThat(rule.get("status").asText()).isEqualTo("passed"));
    }

    @Test
    @DisplayName("the console report ranks failures first and prints the fix")
    void consoleReportIsReadable() {
        String rendered = ConsoleReportWriter.render(reportOfMutableDtoFixture(),
                ReportingConfiguration.builder().color(ColorMode.NEVER).json(false).build());

        assertThat(rendered)
                .contains("Architecture rules")
                .contains("bad.dtos")
                .contains("FAILED")
                .contains(DtoImmutabilityRules.NO_SETTERS_ON_API_MODELS.value())
                .contains("setCode")
                .contains("Fix: ");
        assertThat(rendered).doesNotContain(String.valueOf((char) 27));
    }

    @Test
    @DisplayName("colour is applied only when asked for")
    void consoleColoursAreOptional() {
        ArchitectureReport report = reportOfMutableDtoFixture();

        assertThat(ConsoleReportWriter.render(report,
                ReportingConfiguration.builder().color(ColorMode.ALWAYS).build()))
                .contains(String.valueOf((char) 27));
        assertThat(ConsoleReportWriter.render(report,
                ReportingConfiguration.builder().color(ColorMode.NEVER).build()))
                .doesNotContain(String.valueOf((char) 27));
    }

    @Test
    @DisplayName("the console truncates long violation lists, the JSON never does")
    void consoleTruncatesButJsonDoesNot() throws Exception {
        ArchitectureReport report = ArchitectureRuleRunner.run(ArchitectureRules.suite(
                Fixtures.configurationFor("bad.boundary").build()));
        RuleReport busiest = report.failures().stream()
                .max((first, second) -> Integer.compare(first.violations().size(), second.violations().size()))
                .orElseThrow();

        String rendered = ConsoleReportWriter.render(report,
                ReportingConfiguration.builder().color(ColorMode.NEVER).maxViolationsPerRule(1).build());

        assertThat(busiest.violations().size()).isGreaterThan(1);
        assertThat(rendered).contains("more (see the JSON report)");
        JsonNode json = JSON.readTree(JsonReportWriter.toJson(report));
        assertThat(ruleNode(json, busiest.id().value()).get("violations"))
                .hasSize(busiest.violations().size());
    }

    @Test
    @DisplayName("both reports are written where the configuration says")
    void reportsAreWrittenToDisk(@TempDir Path directory) throws Exception {
        Path jsonFile = directory.resolve("nested").resolve("architecture-report.json");
        ByteArrayOutputStream console = new ByteArrayOutputStream();

        ArchitectureReports.write(reportOfMutableDtoFixture(),
                ReportingConfiguration.builder().color(ColorMode.NEVER).jsonFile(jsonFile).build(),
                new PrintStream(console, true, StandardCharsets.UTF_8));

        assertThat(jsonFile).exists();
        assertThat(JSON.readTree(Files.readString(jsonFile)).get("schemaVersion").asText())
                .isEqualTo(ArchitectureReport.SCHEMA_VERSION);
        assertThat(console.toString(StandardCharsets.UTF_8)).contains("Architecture rules");
    }

    @Test
    @DisplayName("a rule downgraded to a warning is reported everywhere but does not fail the run")
    void warningsAreReportedWithoutFailing() throws Exception {
        ArchitectureRulesConfiguration configuration = Fixtures.configurationFor("bad.dtos")
                .warnOn(DtoImmutabilityRules.NO_SETTERS_ON_API_MODELS.value())
                .build();

        ArchitectureReport report = ArchitectureRuleRunner.run(ArchitectureRules.suite(configuration));

        assertThat(report.warnings()).extracting(rule -> rule.id().value())
                .contains(DtoImmutabilityRules.NO_SETTERS_ON_API_MODELS.value());
        assertThat(report.failures()).extracting(rule -> rule.id().value())
                .doesNotContain(DtoImmutabilityRules.NO_SETTERS_ON_API_MODELS.value());

        JsonNode rule = ruleNode(JSON.readTree(JsonReportWriter.toJson(report)),
                DtoImmutabilityRules.NO_SETTERS_ON_API_MODELS.value());
        assertThat(rule.get("severity").asText()).isEqualTo("warning");
        assertThat(rule.get("status").asText()).isEqualTo("violated");

        assertThat(ConsoleReportWriter.render(report,
                ReportingConfiguration.builder().color(ColorMode.NEVER).build()))
                .contains("WARNING");
    }

    @Test
    @DisplayName("every built-in rule ships the fix for its violations")
    void everyBuiltInRuleHasRemediation() {
        ArchitectureReport report = ArchitectureRuleRunner.run(ArchitectureRules.suite(
                Fixtures.withSharedTypes(Fixtures.configurationFor("good").enable("*"), "good",
                                "catalog.repository.entity.BaseEntity",
                                "catalog.support.ApplicationException",
                                "catalog.service.ProductEventPublisher")
                        .build()));

        assertThat(report.rules()).isNotEmpty();
        assertThat(report.rules())
                // rules a consuming service contributes are its own business; this asserts the
                // library's own coverage, including the ones published through the ServiceLoader here
                .filteredOn(rule -> rule.group() != RuleGroup.CUSTOM)
                .allSatisfy(rule -> assertThat(rule.remediation())
                        .as("remediation of " + rule.id())
                        .isNotBlank());
    }

    @Test
    @DisplayName("a warned rule does not fail the runner-independent check either")
    void checkRespectsSeverity() {
        ArchitectureRulesConfiguration failing = Fixtures.configurationFor("bad.dtos").build();
        ArchitectureRulesConfiguration warned = Fixtures.configurationFor("bad.dtos")
                .warnOn(DtoImmutabilityRules.NO_SETTERS_ON_API_MODELS.value())
                .build();

        assertThatThrownBy(() -> ArchitectureRules.suite(failing).check())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(DtoImmutabilityRules.NO_SETTERS_ON_API_MODELS.value());
        // the same violation, downgraded: reported by the runner, ignored by the check
        assertThatCode(() -> ArchitectureRules.suite(warned).check()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a run with every rule switched off reports that nothing was checked")
    void anEmptyRunIsNotAGreenRun() {
        ArchitectureRulesConfiguration nothingEnabled = Fixtures.configurationFor("good")
                .disable("*")
                .build();

        assertThat(ConsoleReportWriter.render(
                ArchitectureRuleRunner.run(ArchitectureRules.suite(nothingEnabled)),
                ReportingConfiguration.builder().color(ColorMode.NEVER).json(false).build()))
                .contains("Nothing was checked");
        assertThatThrownBy(() -> ArchitectureRules.checkAndReport(nothingEnabled))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("No architecture rule is enabled");
    }

    private static JsonNode ruleNode(JsonNode report, String ruleId) {
        for (JsonNode rule : report.get("rules")) {
            if (ruleId.equals(rule.get("id").asText())) {
                return rule;
            }
        }
        throw new AssertionError("No rule " + ruleId + " in the report");
    }
}
