package ru.ludwigandreas.archrules.report;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One service's architecture check, in full.
 *
 * <p>This is the object both reports are rendered from, and the shape the JSON file follows. It
 * carries the service identity and the base packages alongside the results on purpose: the reports of
 * many microservices are meant to be pooled into one dashboard, and a result row is only meaningful
 * next to the service it came from and the schema version it was written with.
 *
 * @param schemaVersion  version of the JSON schema, so an aggregator can migrate old reports
 * @param toolVersion    version of this library, when it can be read from the jar manifest
 * @param serviceName    the service the report is about
 * @param basePackages   the packages that were analysed
 * @param modules        the modules discovered or configured inside them
 * @param generatedAt    when the run happened, UTC
 * @param durationMillis how long the whole evaluation took
 * @param summary        aggregate counts
 * @param rules          per-rule results, in the order they were evaluated
 */
public record ArchitectureReport(String schemaVersion,
                                 String toolVersion,
                                 String serviceName,
                                 List<String> basePackages,
                                 List<String> modules,
                                 Instant generatedAt,
                                 long durationMillis,
                                 ReportSummary summary,
                                 List<RuleReport> rules) {

    /** Bumped whenever the JSON layout changes in a way an aggregator has to know about. */
    public static final String SCHEMA_VERSION = "1.0.0";

    public ArchitectureReport {
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(serviceName, "serviceName");
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(summary, "summary");
        basePackages = List.copyOf(Objects.requireNonNull(basePackages, "basePackages"));
        modules = List.copyOf(Objects.requireNonNull(modules, "modules"));
        rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
    }

    /** The violated rules that fail the build. */
    public List<RuleReport> failures() {
        return rules.stream().filter(RuleReport::isFailure).toList();
    }

    /** The violated rules the service tolerates at warning severity. */
    public List<RuleReport> warnings() {
        return rules.stream().filter(RuleReport::isWarning).toList();
    }

    public boolean hasFailures() {
        return summary.failed() > 0;
    }
}
