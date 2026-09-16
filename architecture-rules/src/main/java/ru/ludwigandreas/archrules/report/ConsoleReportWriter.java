package ru.ludwigandreas.archrules.report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import ru.ludwigandreas.archrules.RuleGroup;

/**
 * Renders an {@link ArchitectureReport} for a person reading a build log.
 *
 * <p>Ranked rather than exhaustive: failures first, then warnings, each with a handful of violations,
 * the file and line, and the fix - then one line per group for everything that passed. A build log
 * that prints all forty rules is a build log nobody reads, and the full detail is a file away in the
 * JSON report, whose path is printed at the end.
 */
public final class ConsoleReportWriter {

    private static final String SEPARATOR = "-".repeat(78);

    private ConsoleReportWriter() {
    }

    public static String render(ArchitectureReport report, ReportingConfiguration configuration) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(configuration, "configuration");
        Ansi ansi = new Ansi(configuration.color().isEnabled());
        StringBuilder out = new StringBuilder();

        out.append(ansi.dim(SEPARATOR)).append(System.lineSeparator());
        out.append(' ').append(ansi.bold("Architecture rules")).append(ansi.dim(" | "))
                .append(report.serviceName()).append(System.lineSeparator());
        out.append(' ').append(summaryLine(report, ansi)).append(System.lineSeparator());
        out.append(ansi.dim(SEPARATOR)).append(System.lineSeparator());

        for (RuleReport rule : report.failures()) {
            appendRule(out, rule, ansi, configuration, ansi.red("FAILED "));
        }
        for (RuleReport rule : report.warnings()) {
            appendRule(out, rule, ansi, configuration, ansi.yellow("WARNING"));
        }

        if (report.rules().isEmpty()) {
            // "All 0 rules passed" would read as a green build; it is a misconfiguration.
            out.append(System.lineSeparator())
                    .append(' ').append(ansi.yellow("NO RULES"))
                    .append("  Nothing was checked: every rule is switched off for this service.")
                    .append(System.lineSeparator());
        } else if (report.summary().isClean()) {
            out.append(System.lineSeparator())
                    .append(' ').append(ansi.green("OK"))
                    .append("  All ").append(report.summary().rules())
                    .append(" architecture rules passed.").append(System.lineSeparator());
        } else {
            out.append(System.lineSeparator())
                    .append(' ').append(ansi.green("passed"))
                    .append(ansi.dim(" | ")).append(passedByGroup(report))
                    .append(System.lineSeparator());
        }
        if (configuration.json()) {
            out.append(' ').append(ansi.dim("JSON report: " + configuration.jsonFile().toAbsolutePath()))
                    .append(System.lineSeparator());
        }
        return out.toString();
    }

    private static String summaryLine(ArchitectureReport report, Ansi ansi) {
        ReportSummary summary = report.summary();
        StringBuilder line = new StringBuilder()
                .append(summary.rules()).append(" rules")
                .append(ansi.dim(" | ")).append(ansi.green(summary.passed() + " passed"));
        if (summary.failed() > 0) {
            line.append(ansi.dim(" | ")).append(ansi.red(summary.failed() + " failed"));
        }
        if (summary.warnings() > 0) {
            line.append(ansi.dim(" | ")).append(ansi.yellow(summary.warnings() + " warned"));
        }
        if (summary.violations() > 0) {
            line.append(ansi.dim(" | ")).append(summary.violations())
                    .append(summary.violations() == 1 ? " violation" : " violations");
        }
        line.append(ansi.dim(" | ")).append(report.durationMillis()).append(" ms");
        return line.toString();
    }

    private static void appendRule(StringBuilder out, RuleReport rule, Ansi ansi,
                                   ReportingConfiguration configuration, String label) {
        out.append(System.lineSeparator())
                .append(' ').append(label).append("  ").append(ansi.bold(rule.displayName()))
                .append(ansi.dim(" (" + rule.violations().size()
                        + (rule.violations().size() == 1 ? " violation)" : " violations)")))
                .append(System.lineSeparator())
                .append("   ").append(ansi.dim(rule.description())).append(System.lineSeparator());

        List<ViolationDetail> shown = rule.violations().size() > configuration.maxViolationsPerRule()
                ? rule.violations().subList(0, configuration.maxViolationsPerRule())
                : rule.violations();
        for (ViolationDetail violation : shown) {
            out.append("   - ").append(violation.message()).append(System.lineSeparator());
        }
        int remaining = rule.violations().size() - shown.size();
        if (remaining > 0) {
            out.append("   ").append(ansi.dim("... " + remaining + " more (see the JSON report)"))
                    .append(System.lineSeparator());
        }
        if (!rule.remediation().isEmpty()) {
            out.append("   ").append(ansi.cyan("Fix: ")).append(rule.remediation())
                    .append(System.lineSeparator());
        }
    }

    private static String passedByGroup(ArchitectureReport report) {
        Map<RuleGroup, Integer> passedByGroup = new LinkedHashMap<>();
        for (RuleReport rule : report.rules()) {
            if (!rule.isViolated()) {
                passedByGroup.merge(rule.group(), 1, Integer::sum);
            }
        }
        StringBuilder groups = new StringBuilder();
        passedByGroup.forEach((group, count) -> {
            if (groups.length() > 0) {
                groups.append(", ");
            }
            groups.append(group.id()).append(' ').append(count);
        });
        return groups.length() == 0 ? "nothing" : groups.toString();
    }

    /**
     * ANSI escapes, or plain text when the output is not a terminal a human is watching. The escape
     * character is built from its code point rather than written literally, so that the source file
     * stays free of control characters.
     */
    private record Ansi(boolean enabled) {

        private static final String ESCAPE = String.valueOf((char) 27);
        private static final String RESET = ESCAPE + "[0m";

        String bold(String text) {
            return wrap(text, "[1m");
        }

        String dim(String text) {
            return wrap(text, "[2m");
        }

        String red(String text) {
            return wrap(text, "[31m");
        }

        String green(String text) {
            return wrap(text, "[32m");
        }

        String yellow(String text) {
            return wrap(text, "[33m");
        }

        String cyan(String text) {
            return wrap(text, "[36m");
        }

        private String wrap(String text, String code) {
            return enabled ? ESCAPE + code + text + RESET : text;
        }
    }
}
