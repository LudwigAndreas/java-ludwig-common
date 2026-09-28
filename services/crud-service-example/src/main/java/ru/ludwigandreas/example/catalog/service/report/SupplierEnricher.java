package ru.ludwigandreas.example.catalog.service.report;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import ru.ludwigandreas.example.catalog.client.SupplierDirectoryApi;
import ru.ludwigandreas.example.catalog.client.dto.SupplierSummary;
import ru.ludwigandreas.export.api.Enricher;

/**
 * Resolves supplier ids against the supplier directory, a window's worth at a time.
 *
 * <h2>Everything this class deliberately does not do</h2>
 *
 * <p>It does not catch anything. A failure from the directory - an exhausted retry, an open circuit
 * breaker, a 500 - propagates, and the export engine applies the stage's {@code FailurePolicy} to it.
 * Catching it here would produce a report that looks complete and is not, which is the failure mode
 * the whole degradation apparatus exists to make impossible.
 *
 * <p>It does not substitute anything for a key the directory did not return. Absence is a first-class
 * outcome with its own policy and its own counter; a fabricated supplier name is a wrong value in a
 * document somebody will make a purchasing decision on.
 *
 * <p>It does not cache, retry, time out, or bound its own concurrency. The retry and the timeout are
 * the {@code suppliers} REST client's, the per-run cache is the engine's, and the concurrency is the
 * stage's. A second layer of any of them would be invisible to the operator reading the first.
 *
 * <p>It does not page. The engine never hands it more keys than the stage's declared batch size, which
 * is the number the directory's query-string ceiling actually permits.
 */
@RequiredArgsConstructor
public class SupplierEnricher implements Enricher.Batched<String, SupplierSummary> {

    private final SupplierDirectoryApi directory;

    /**
     * Asks the directory for a chunk of ids.
     *
     * <p>The answer is re-keyed rather than returned as a list because the engine joins on the key,
     * and a directory is entitled to answer in any order. A duplicate id in the answer keeps the first
     * entry: two records for one supplier is the directory's defect, and picking the later one would
     * make the report's contents depend on the order a partner happened to serialize.
     *
     * @param keys the supplier ids in this chunk
     * @return the suppliers found, by id; an id the directory does not know is simply absent
     */
    @Override
    public Map<String, SupplierSummary> fetchBatch(Collection<String> keys) {
        Map<String, SupplierSummary> byId = new LinkedHashMap<>();
        for (SupplierSummary supplier : directory.byIds(keys)) {
            if (supplier != null && supplier.id() != null) {
                byId.putIfAbsent(supplier.id(), supplier);
            }
        }
        return byId;
    }
}
