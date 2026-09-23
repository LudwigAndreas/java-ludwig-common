package ru.ludwigandreas.jira.structure.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The payload for changing a forest: which forest, which version of it the caller saw, and what to do.
 *
 * <p>The version is not optional in practice. Structure uses it to reject an update computed against a
 * forest that has since changed, which is the only thing standing between two concurrent reorderings and
 * silent data loss - see {@link ForestVersion}.
 *
 * @param spec the forest to change
 * @param version the version the caller last observed
 * @param actions the edits, applied in order
 */
public record ForestUpdateRequest(@JsonProperty("spec") ForestSpec spec,
                                  @JsonProperty("version") ForestVersion version,
                                  @JsonProperty("actions") List<ForestAction> actions) {

    /** Normalizes {@code actions} to an immutable list. */
    public ForestUpdateRequest {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }
}
