package ru.ludwigandreas.archrules.report;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Emits the reports a run produces.
 *
 * <p>The console report goes out first and the file second, so that a build whose report directory is
 * unwritable still shows the architecture failures before it complains about the path. An unwritable
 * report path is itself an error rather than something to shrug off: a pipeline that collects the JSON
 * would otherwise keep publishing yesterday's file, or nothing at all, and nobody would notice.
 */
public final class ArchitectureReports {

    private ArchitectureReports() {
    }

    /** Writes whichever reports the configuration asks for; returns the JSON file, if one was written. */
    public static Optional<Path> write(ArchitectureReport report, ReportingConfiguration configuration) {
        return write(report, configuration, System.out);
    }

    /** Same, with an explicit console stream - the seam the tests use. */
    public static Optional<Path> write(ArchitectureReport report,
                                       ReportingConfiguration configuration,
                                       PrintStream console) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(console, "console");
        if (configuration.console()) {
            console.print(ConsoleReportWriter.render(report, configuration));
            console.flush();
        }
        if (!configuration.json()) {
            return Optional.empty();
        }
        Path jsonFile = configuration.jsonFile();
        try {
            Path directory = jsonFile.toAbsolutePath().getParent();
            if (directory != null) {
                Files.createDirectories(directory);
            }
            Files.writeString(jsonFile, JsonReportWriter.toJson(report), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("Failed to write the architecture report to "
                    + jsonFile.toAbsolutePath(), failure);
        }
        return Optional.of(jsonFile);
    }
}
