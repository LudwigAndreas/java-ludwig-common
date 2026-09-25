package ru.ludwigandreas.export.enrich;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import ru.ludwigandreas.export.api.EnrichmentStage;
import ru.ludwigandreas.export.config.ExportProperties;
import ru.ludwigandreas.export.i18n.ExportMessages;
import ru.ludwigandreas.export.metrics.ExportMetrics;

/**
 * Holds what every run's enrichment shares, and makes the per-run state that none of them may share.
 *
 * <p>The executor, the settings, the metrics and the message source are the same for every run and
 * live here. The cache, the dimension catalogues and the set of degraded stages belong to exactly
 * one run and live on {@link EnrichmentRun} - which is not an implementation detail but the point:
 * a cache shared between runs would make a report a mixture of two points in time, and a degraded
 * set shared between them would let one partner's outage mark a run that started after it recovered.
 */
public class EnrichmentExecutor {

    private final Executor executor;
    private final EnrichmentSettings settings;
    private final ExportMetrics metrics;
    private final ExportMessages messages;

    /**
     * Creates the executor.
     *
     * @param executor the shared pool a stage's calls fan out on, or null to call on the run's own
     *                 thread - which is what a service with no pool configured gets, and is correct
     *                 rather than degraded for a definition whose stages are all {@code Dimension}
     * @param defaults the module-wide enrichment settings a stage inherits
     * @param metrics  where cache, call and degradation counts go
     * @param messages resolves the markers a gap or an outage writes into a cell
     */
    public EnrichmentExecutor(Executor executor, ExportProperties.Enrichment defaults,
                              ExportMetrics metrics, ExportMessages messages) {
        this.executor = executor;
        this.settings = new EnrichmentSettings(defaults);
        this.metrics = metrics;
        this.messages = messages;
    }

    /**
     * Starts the enrichment side of one run.
     *
     * @param definitionKey which report, for the metric tags
     * @param stages        the definition's stages, in declaration order
     * @param locale        the run's locale, for the markers
     * @param cacheSize     entry ceiling for this run's cache
     * @param <R>           the row type
     * @return per-run state; used by one thread, thrown away when the run ends
     */
    public <R> EnrichmentRun<R> startRun(String definitionKey, List<EnrichmentStage<R, ?, ?>> stages,
                                         Locale locale, int cacheSize) {
        return new EnrichmentRun<>(definitionKey, stages, new EnrichmentCache(cacheSize), executor,
                metrics, messages, locale, settings);
    }

    /** The settings a stage inherits, for a caller that needs to report them. */
    public EnrichmentSettings settings() {
        return settings;
    }
}
