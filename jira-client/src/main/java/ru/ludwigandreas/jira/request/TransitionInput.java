package ru.ludwigandreas.jira.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * The payload for executing a workflow transition, optionally setting fields and adding a comment in the
 * same atomic step.
 *
 * <p>Setting fields through the transition is not the same as updating the issue and then transitioning it.
 * Only the transition form can satisfy a transition screen's required fields - a resolution that the
 * workflow demands on "Resolve Issue" cannot be set by a prior edit, because the field is not on the edit
 * screen - and only the transition form is atomic, so a failure leaves the issue in its original state
 * rather than edited-but-not-moved.
 *
 * @param transition the transition to execute, by id
 * @param fields field values to apply as part of the transition
 * @param update per-field operations to apply as part of the transition
 */
public record TransitionInput(@JsonProperty("transition") Map<String, String> transition,
                              @JsonProperty("fields") Map<String, Object> fields,
                              @JsonProperty("update") Map<String, List<Map<String, Object>>> update) {

    /** A bare transition with no field changes. */
    public static TransitionInput of(String transitionId) {
        return new TransitionInput(Map.of("id", transitionId), null, null);
    }

    /** A transition carrying an issue payload - fields, operations, a comment. */
    public static TransitionInput of(String transitionId, IssueInput input) {
        return new TransitionInput(
                Map.of("id", transitionId),
                input.fields().isEmpty() ? null : input.fields(),
                input.update().isEmpty() ? null : input.update());
    }
}
