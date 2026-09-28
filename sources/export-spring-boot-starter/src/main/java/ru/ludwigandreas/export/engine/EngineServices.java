package ru.ludwigandreas.export.engine;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import ru.ludwigandreas.export.api.ReportSink;
import ru.ludwigandreas.export.api.ReportWriterFactory;
import ru.ludwigandreas.export.enrich.EnrichmentExecutor;
import ru.ludwigandreas.export.i18n.ExportMessages;
import ru.ludwigandreas.export.metrics.ExportMetrics;

/**
 * The collaborators every run shares, gathered so that a run is constructed from two things rather
 * than from nine.
 *
 * <p>A record rather than a longer constructor on {@link ReportRunAttempt}: those arguments are the
 * same for every run in the process and change only when the context is built, so threading them
 * through one at a time made the attempt's signature grow with every phase of this module while
 * saying nothing about the run itself. What belongs to <em>a</em> run is the plan; what belongs to
 * <em>every</em> run is here.
 *
 * @param factories        the registered writer factories, by format id
 * @param sink             where finished files go
 * @param tempFiles        where partial files live, and what removes them
 * @param messages         resolves headers, titles and markers into the run's locale
 * @param clock            injected so a run's wall-clock budget is deterministic in tests
 * @param enrichment       makes the per-run cache, catalogues and degraded set
 * @param metrics          where run, call and cache counts go
 * @param prefetchExecutor where a run's producer thread comes from, or null to read on the caller's
 */
public record EngineServices(
        Map<String, ReportWriterFactory> factories,
        ReportSink sink,
        TempFiles tempFiles,
        ExportMessages messages,
        Clock clock,
        EnrichmentExecutor enrichment,
        ExportMetrics metrics,
        ExecutorService prefetchExecutor) {

    // SUPPRESS CHECKSTYLE ParameterNumber - a record's canonical constructor; every component is
    // named at the call site by construction, which is the readability the rule protects.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public EngineServices {
        if (factories == null) {
            throw new IllegalArgumentException("EngineServices needs the writer factories");
        }
        if (sink == null || tempFiles == null || messages == null || clock == null) {
            throw new IllegalArgumentException(
                    "EngineServices needs a sink, a temp-file manager, messages and a clock");
        }
    }
}
