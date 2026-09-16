package ru.ludwigandreas.archrules.report;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * What the runner emits after a run: a human-readable console report, a machine-readable JSON report,
 * or both.
 *
 * <p>Two formats rather than one because they have different readers. The console report is for the
 * person whose build just went red: colourised, ranked by severity, showing a few violations per rule
 * and the fix. The JSON report is for everything else - a coding agent asked to clean the violations
 * up, a pull-request bot, and the org-wide dashboard that pools the reports of every microservice to
 * see where architecture drift is accumulating. Neither is a rendering of the other; both come from
 * the same {@link ArchitectureReport}.
 */
public final class ReportingConfiguration {

    private static final String DEFAULT_REPORT_FILE_NAME = "architecture-report.json";

    private final boolean console;
    private final ColorMode color;
    private final boolean json;
    private final Path jsonFile;
    private final int maxViolationsPerRule;

    private ReportingConfiguration(Builder builder) {
        this.console = builder.console;
        this.color = builder.color;
        this.json = builder.json;
        this.jsonFile = builder.jsonFile != null ? builder.jsonFile : defaultReportFile();
        this.maxViolationsPerRule = builder.maxViolationsPerRule;
    }

    public static ReportingConfiguration defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.console = console;
        builder.color = color;
        builder.json = json;
        builder.jsonFile = jsonFile;
        builder.maxViolationsPerRule = maxViolationsPerRule;
        return builder;
    }

    public boolean console() {
        return console;
    }

    public ColorMode color() {
        return color;
    }

    public boolean json() {
        return json;
    }

    public Path jsonFile() {
        return jsonFile;
    }

    /**
     * How many violations of one rule the console prints before summarising the rest. The JSON report
     * is never truncated - a console report is read, a JSON report is processed.
     */
    public int maxViolationsPerRule() {
        return maxViolationsPerRule;
    }

    /**
     * The build tool's output directory, so the report lands where CI already collects artifacts from
     * without anybody configuring a path.
     */
    private static Path defaultReportFile() {
        if (Files.exists(Path.of("pom.xml"))) {
            return Path.of("target", DEFAULT_REPORT_FILE_NAME);
        }
        if (Files.exists(Path.of("build.gradle")) || Files.exists(Path.of("build.gradle.kts"))) {
            return Path.of("build", "reports", "architecture", DEFAULT_REPORT_FILE_NAME);
        }
        return Path.of("target", DEFAULT_REPORT_FILE_NAME);
    }

    @Override
    public String toString() {
        return "ReportingConfiguration{console=" + console + ", color=" + color
                + ", json=" + json + ", jsonFile=" + jsonFile + '}';
    }

    /** Builder for {@link ReportingConfiguration}. */
    public static final class Builder {

        private boolean console = true;
        private ColorMode color = ColorMode.AUTO;
        private boolean json = true;
        private Path jsonFile;
        private int maxViolationsPerRule = 5;

        private Builder() {
        }

        public Builder console(boolean enabled) {
            this.console = enabled;
            return this;
        }

        public Builder color(ColorMode mode) {
            this.color = Objects.requireNonNull(mode, "mode");
            return this;
        }

        public Builder json(boolean enabled) {
            this.json = enabled;
            return this;
        }

        public Builder jsonFile(Path file) {
            this.jsonFile = Objects.requireNonNull(file, "file");
            return this;
        }

        public Builder maxViolationsPerRule(int max) {
            if (max < 1) {
                throw new IllegalArgumentException("At least one violation per rule must be printed, got " + max);
            }
            this.maxViolationsPerRule = max;
            return this;
        }

        public ReportingConfiguration build() {
            return new ReportingConfiguration(this);
        }
    }
}
