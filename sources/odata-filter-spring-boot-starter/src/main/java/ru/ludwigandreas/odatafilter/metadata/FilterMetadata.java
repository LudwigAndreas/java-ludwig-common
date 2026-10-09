package ru.ludwigandreas.odatafilter.metadata;

import java.util.List;

/**
 * What one caller may ask about one entity: the properties it may name, and the limits the server will
 * enforce on the query it writes.
 *
 * <h2>Why this is not {@code $metadata}</h2>
 *
 * <p>OData's {@code $metadata} is a CSDL document describing a service: entity sets, navigation
 * properties, actions, functions, an entity container. This module implements {@code $filter},
 * {@code $orderby}, {@code $top}, {@code $skip} and {@code $count} and none of the rest, so a document
 * calling itself {@code $metadata} while describing only the filterable subset would mislead every generic
 * OData client that found it - which is worse than not having one. This describes the filter policy, and
 * is named for that.
 *
 * <h2>Why it exists at all</h2>
 *
 * <p>Without it the only way to discover the filterable surface is to send filters and read the 400s and
 * 403s. This module's own README documents a rise in {@code odata.filter.rejected} as "either a client bug
 * or someone probing for what's filterable - worth alerting on either way", which made the module the cause
 * of the behaviour it told operators to alarm on.
 *
 * <h2>What it is a projection of</h2>
 *
 * <p>Every field below is read from the resolved {@code EntityFilterPolicy}, which is itself resolved from
 * the entity's {@code @FilterPolicy} and {@code @Filterable} annotations. There is deliberately no second
 * list: a field list maintained beside the annotations would drift, and the drift would be invisible
 * precisely in the cases that matter - a field opened in code and still absent from the document, or
 * absent from code and still advertised.
 *
 * @param entity                 the published name, from {@code @FilterPolicy(metadataName = ...)}
 * @param properties             the paths this caller may name, sorted; a path it may not use is absent
 * @param maxDepth               how deeply a {@code $filter} may nest before it is refused
 * @param maxPageSize            the largest {@code $top} this endpoint accepts
 * @param defaultPageSize        the page size applied when the caller sends no {@code $top}
 * @param maxNestedPropertyDepth how many associations a property path may traverse
 * @param defaultOrderBy         the server-side ordering appended to the caller's {@code $orderby}, in
 *                               {@code $orderby} syntax, or empty when the entity declares none
 */
public record FilterMetadata(
        String entity,
        List<FilterPropertyMetadata> properties,
        int maxDepth,
        int maxPageSize,
        int defaultPageSize,
        int maxNestedPropertyDepth,
        String defaultOrderBy) {

    public FilterMetadata {
        properties = properties == null ? List.of() : List.copyOf(properties);
    }
}
