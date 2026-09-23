package ru.ludwigandreas.jira.structure.api;

import java.util.List;
import ru.ludwigandreas.jira.JiraRestClient;
import ru.ludwigandreas.jira.api.ApiPaths;
import ru.ludwigandreas.jira.structure.model.AttributeSpec;
import ru.ludwigandreas.jira.structure.model.ForestSpec;
import ru.ludwigandreas.jira.structure.model.ValueRequest;
import ru.ludwigandreas.jira.structure.model.ValueResponse;

/**
 * Computed column values for the rows of a forest.
 *
 * <p>Reached through {@link ru.ludwigandreas.jira.JiraClient#structureValues()}.
 *
 * <p>This is the endpoint that justifies integrating with Structure at all rather than reading the issues
 * directly. The values it returns are not issue fields: they are the columns Structure computes over the
 * hierarchy - a sum rolled up across a subtree, a progress percentage, a formula column - and they exist
 * nowhere in Jira's own API, because Jira has no hierarchy to roll anything up over.
 *
 * <p>A value request is always two calls: read the forest to get its row ids, then ask for values on those
 * rows. There is no way to skip the first - row ids are the only handle the value endpoint accepts, and
 * they are meaningful only within the forest that produced them.
 *
 * <p>Marked retryable: it is a read that happens to be a {@code POST} because its payload is a document.
 */
public final class StructureValueApi {

    private static final String VALUE = ApiPaths.STRUCTURE_2 + "/value";

    private final JiraRestClient rest;

    public StructureValueApi(JiraRestClient rest) {
        this.rest = rest;
    }

    /**
     * Computes several blocks of values in one call.
     *
     * @param requests the blocks, each naming a forest, its rows and the attributes to compute
     * @return one response block per request block, in order
     */
    public ValueResponse compute(List<ValueRequest> requests) {
        return rest.post(VALUE)
                .operation("structure.value.compute")
                .retryable(true)
                .body(java.util.Map.of("requests", requests))
                .as(ValueResponse.class);
    }

    /**
     * Computes one block of values.
     *
     * @param spec the forest the rows belong to
     * @param rowIds the rows to compute values for
     * @param attributes the columns to compute
     * @return the response, whose single block holds the values
     */
    public ValueResponse compute(ForestSpec spec, List<Long> rowIds, List<AttributeSpec> attributes) {
        return compute(List.of(new ValueRequest(spec, rowIds, attributes)));
    }
}
