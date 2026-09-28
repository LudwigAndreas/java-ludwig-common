package ru.ludwigandreas.jira.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import ru.ludwigandreas.jira.model.common.Visibility;

/**
 * The payload for logging work.
 *
 * <p>Supply exactly one of {@code timeSpent} ({@code "3h 30m"}) and {@code timeSpentSeconds}. Jira rejects a
 * payload carrying both, and silently logs nothing useful when it carries neither.
 *
 * <p>{@code started} is when the work happened, and defaults to now if omitted - which is almost never what
 * a batch import wants. How the remaining estimate is adjusted is a separate concern and lives in
 * {@link EstimateAdjustment}, because it is sent as query parameters rather than in this body.
 *
 * @param comment worklog comment, in Jira wiki markup
 * @param started when the work was performed
 * @param timeSpent duration in Jira syntax
 * @param timeSpentSeconds duration in seconds
 * @param visibility restriction to a group or project role
 */
public record WorklogInput(@JsonProperty("comment") String comment,
                           @JsonProperty("started") OffsetDateTime started,
                           @JsonProperty("timeSpent") String timeSpent,
                           @JsonProperty("timeSpentSeconds") Long timeSpentSeconds,
                           @JsonProperty("visibility") Visibility visibility) {

    /** Rejects the payload Jira rejects, at the point where the mistake is still cheap to see. */
    public WorklogInput {
        if (timeSpent != null && timeSpentSeconds != null) {
            throw new IllegalArgumentException(
                    "Set either timeSpent or timeSpentSeconds, not both - Jira rejects a worklog carrying both");
        }
        if (timeSpent == null && timeSpentSeconds == null) {
            throw new IllegalArgumentException("A worklog needs either timeSpent or timeSpentSeconds");
        }
    }

    /** Work of the given duration, performed at the given time. */
    public static WorklogInput of(String timeSpent, OffsetDateTime started) {
        return new WorklogInput(null, started, timeSpent, null, null);
    }

    /** Work of the given duration, performed at the given time, with a comment. */
    public static WorklogInput of(String timeSpent, OffsetDateTime started, String comment) {
        return new WorklogInput(comment, started, timeSpent, null, null);
    }
}
