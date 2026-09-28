package ru.ludwigandreas.jira.structure.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * One block of a Structure value request: which rows of which forest, and which columns to compute.
 *
 * <p>The row ids come from a forest read, and they must come from the <em>same</em> forest specification
 * named here - a row id is meaningful only within the forest that produced it.
 *
 * @param forestSpec the forest the rows belong to
 * @param rows the row ids to compute values for
 * @param attributes the columns to compute
 */
public record ValueRequest(@JsonProperty("forestSpec") ForestSpec forestSpec,
                           @JsonProperty("rows") List<Long> rows,
                           @JsonProperty("attributes") List<AttributeSpec> attributes) {

    /** Normalizes the two collections to immutable lists. */
    public ValueRequest {
        rows = rows == null ? List.of() : List.copyOf(rows);
        attributes = attributes == null ? List.of() : List.copyOf(attributes);
    }
}
