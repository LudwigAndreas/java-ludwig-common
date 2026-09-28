package ru.ludwigandreas.export.enrich;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import ru.ludwigandreas.export.api.EnrichmentStage;
import ru.ludwigandreas.export.exception.ExportConfigurationException;

/**
 * Groups a definition's stages into levels that can be fetched concurrently.
 *
 * <p>A level is a set of stages whose declared dependencies have all been satisfied by earlier
 * levels, so everything in one level is independent of everything else in it and can go out at
 * once. Computed per run rather than per window, because the answer depends only on the definition
 * and recomputing it five hundred times would be five hundred topological sorts of the same graph.
 *
 * <p>A cycle is a configuration error and is reported as one. The registry catches it at startup;
 * this check is the second line, for a definition assembled at runtime by something that bypassed
 * the registry - and it exists because the alternative is an infinite loop rather than a wrong
 * answer.
 */
final class StageOrdering {

    private StageOrdering() {
    }

    static <R> List<List<EnrichmentStage<R, ?, ?>>> levels(List<EnrichmentStage<R, ?, ?>> stages) {
        if (stages == null || stages.isEmpty()) {
            return List.of();
        }
        List<EnrichmentStage<R, ?, ?>> remaining = new ArrayList<>(stages);
        Set<String> satisfied = new LinkedHashSet<>();
        List<List<EnrichmentStage<R, ?, ?>>> levels = new ArrayList<>();
        while (!remaining.isEmpty()) {
            List<EnrichmentStage<R, ?, ?>> level = new ArrayList<>();
            for (EnrichmentStage<R, ?, ?> stage : remaining) {
                if (satisfied.containsAll(stage.getDependsOn())) {
                    level.add(stage);
                }
            }
            if (level.isEmpty()) {
                throw new ExportConfigurationException(
                        "Enrichment stages depend on each other in a cycle: "
                                + remaining.stream().map(EnrichmentStage::getName).toList());
            }
            level.forEach(stage -> satisfied.add(stage.getName()));
            remaining.removeAll(level);
            levels.add(List.copyOf(level));
        }
        return List.copyOf(levels);
    }
}
