package ru.ludwigandreas.export.enrich;

import ru.ludwigandreas.export.api.CallIdentity;
import ru.ludwigandreas.export.api.EnrichmentStage;
import ru.ludwigandreas.export.config.ExportProperties;

/**
 * Resolves a stage's settings against the module defaults.
 *
 * <p>Written out rather than left to relaxed binding, for the reason {@code reconciliation}'s
 * {@code TaskSettingsResolver} exists: a stage's overrides are nullable boxes precisely so that
 * "not said here" is distinguishable from "said zero", and nothing in Spring merges two objects
 * like that on its own. A service that assumed otherwise would find its defaults quietly replaced
 * by nulls.
 *
 * @param defaults the module-wide enrichment settings
 */
public record EnrichmentSettings(ExportProperties.Enrichment defaults) {

    /** Keys per call for a batched stage. */
    public int batchSize(EnrichmentStage<?, ?, ?> stage) {
        return stage.getBatchSize() == null ? defaults.getBatchSize() : stage.getBatchSize();
    }

    /** Concurrent in-flight calls for this stage within one window. */
    public int concurrency(EnrichmentStage<?, ?, ?> stage) {
        return stage.getConcurrency() == null ? defaults.getConcurrency() : stage.getConcurrency();
    }

    /** Whether this stage's values are cached for the life of the run. */
    public boolean cacheEnabled(EnrichmentStage<?, ?, ?> stage) {
        return defaults.isCacheEnabled() && stage.isCacheEnabled();
    }

    /**
     * Whose credentials this stage's partner calls carry.
     *
     * <p>Unlike {@link #cacheEnabled}, the module default does not constrain the stage: a stage that
     * declares {@code REQUESTER} gets it even where the default is {@code SERVICE_ACCOUNT}, because the
     * two are different trust decisions rather than a switch and an override of it. Whether the stage
     * can actually have what it asked for is checked at startup against its REST client, and again at
     * plan time against the thread the run is on.
     */
    public CallIdentity callAs(EnrichmentStage<?, ?, ?> stage) {
        return stage.getCallAs() == null ? defaults.getCallAs() : stage.getCallAs();
    }

    /** Entry ceiling for a dimension stage's catalogue. */
    public int maxDimensionEntries(EnrichmentStage<?, ?, ?> stage) {
        return stage.getMaxDimensionEntries() == null
                ? defaults.getMaxDimensionEntries()
                : stage.getMaxDimensionEntries();
    }
}
