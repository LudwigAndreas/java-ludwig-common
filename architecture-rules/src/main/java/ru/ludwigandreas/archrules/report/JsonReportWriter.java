package ru.ludwigandreas.archrules.report;

import java.util.Map;
import java.util.Objects;

import ru.ludwigandreas.archrules.RuleGroup;

/**
 * Renders an {@link ArchitectureReport} as JSON.
 *
 * <h2>Schema</h2>
 * <pre>{@code
 * {
 *   "schemaVersion": "1.0.0",
 *   "tool":    { "name": "ru.ludwigandreas:architecture-rules", "version": "1.0.0" },
 *   "service": { "name": "orders", "basePackages": [...], "modules": [...] },
 *   "generatedAt": "2026-02-01T10:00:00Z",
 *   "durationMillis": 1234,
 *   "summary": { "rules": 41, "passed": 38, "failed": 2, "warnings": 1, "violations": 9,
 *                "violationsByGroup": { "web": 4, "persistence": 5 } },
 *   "rules": [ { "id": "web.controllers-do-not-expose-entities", "group": "web", "scope": "service",
 *                "severity": "error", "status": "violated", "description": "...",
 *                "remediation": "...", "durationMillis": 12, "violationCount": 3,
 *                "violations": [ { "message": "...", "class": "...", "member": "...",
 *                                  "sourceFile": "...", "line": 66,
 *                                  "location": "ProductController.java:66" } ] } ]
 * }
 * }</pre>
 *
 * <p>Three decisions in that shape are deliberate. Rules that <em>passed</em> are included, because an
 * aggregator has to be able to tell "this service checks the Kafka rules and is clean" from "this
 * service does not check them at all" - without that, a dashboard rewards switching rules off.
 * Identity (service, base packages, schema version) travels with every report, so files collected
 * from many repositories can be pooled without a naming convention holding them together. And each
 * rule carries its {@code remediation} next to its violations, so a coding agent handed the file has
 * the fix and the location in one record and needs no access to this library's source.
 */
public final class JsonReportWriter {

    private static final String TOOL_NAME = "ru.ludwigandreas:architecture-rules";

    private JsonReportWriter() {
    }

    public static String toJson(ArchitectureReport report) {
        Objects.requireNonNull(report, "report");
        JsonWriter json = new JsonWriter();
        json.beginObject()
                .field("schemaVersion", report.schemaVersion())
                .name("tool").beginObject()
                .field("name", TOOL_NAME)
                .field("version", report.toolVersion())
                .endObject()
                .name("service").beginObject()
                .field("name", report.serviceName())
                .name("basePackages").beginArray();
        report.basePackages().forEach(json::value);
        json.endArray()
                .name("modules").beginArray();
        report.modules().forEach(json::value);
        json.endArray()
                .endObject()
                .field("generatedAt", report.generatedAt().toString())
                .field("durationMillis", report.durationMillis());

        ReportSummary summary = report.summary();
        json.name("summary").beginObject()
                .field("rules", summary.rules())
                .field("passed", summary.passed())
                .field("failed", summary.failed())
                .field("warnings", summary.warnings())
                .field("violations", summary.violations())
                .name("violationsByGroup").beginObject();
        for (Map.Entry<RuleGroup, Integer> entry : summary.violationsByGroup().entrySet()) {
            json.field(entry.getKey().id(), entry.getValue());
        }
        json.endObject().endObject();

        json.name("rules").beginArray();
        for (RuleReport rule : report.rules()) {
            writeRule(json, rule);
        }
        json.endArray().endObject();
        return json.toJson();
    }

    private static void writeRule(JsonWriter json, RuleReport rule) {
        json.beginObject()
                .field("id", rule.id().value())
                .field("group", rule.group().id())
                .field("scope", rule.scope())
                .field("severity", rule.severity().id())
                .field("status", rule.status().id())
                .field("description", rule.description())
                .field("remediation", rule.remediation())
                .field("durationMillis", rule.durationMillis())
                .field("violationCount", rule.violations().size())
                .name("violations").beginArray();
        for (ViolationDetail violation : rule.violations()) {
            json.beginObject()
                    .field("message", violation.message())
                    .field("class", violation.className())
                    .field("member", violation.memberName())
                    .field("sourceFile", violation.sourceFile())
                    .field("line", violation.lineNumber())
                    .field("location", violation.location())
                    .endObject();
        }
        json.endArray().endObject();
    }
}
