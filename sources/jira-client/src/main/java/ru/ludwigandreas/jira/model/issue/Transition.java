package ru.ludwigandreas.jira.model.issue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;
import ru.ludwigandreas.jira.model.field.FieldMeta;

/**
 * A workflow transition available on an issue right now.
 *
 * <p>Transition ids are per-workflow and are not stable across projects, so an integration must look the
 * transition up by name on the issue it is about to move rather than hard-coding an id - which is what
 * {@link ru.ludwigandreas.jira.api.IssueApi#transitionByName} does.
 *
 * <p>{@code fields} is populated only when the transitions were fetched with
 * {@code expand=transitions.fields}, and it is the authoritative answer to "what must I supply to make this
 * transition succeed" - a transition screen with a required resolution rejects a transition request that
 * omits it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Transition(String id,
                         String name,
                         Status to,
                         Boolean hasScreen,
                         Boolean isGlobal,
                         Boolean isInitial,
                         Boolean isConditional,
                         Map<String, FieldMeta> fields,
                         String expand) {

    /** Normalizes {@code fields} to an immutable empty map rather than {@code null}. */
    public Transition {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }
}
