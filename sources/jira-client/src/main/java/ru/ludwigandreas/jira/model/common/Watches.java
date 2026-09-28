package ru.ludwigandreas.jira.model.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import ru.ludwigandreas.jira.model.user.JiraUser;

/**
 * The {@code watches} field on an issue.
 *
 * <p>{@code watchers} is populated only by the dedicated watchers endpoint, and only for a caller holding
 * the "Manage Watchers" permission; reading an issue returns the count and leaves the list empty. That
 * asymmetry is Jira's, not this client's.
 *
 * @param self absolute URL of the watchers resource
 * @param watchCount number of watchers
 * @param isWatching whether the calling user watches this issue
 * @param watchers the watchers themselves, when the endpoint and permissions allow
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Watches(String self, Integer watchCount, Boolean isWatching, List<JiraUser> watchers) {

    /** Normalizes {@code watchers} to an immutable empty list rather than {@code null}. */
    public Watches {
        watchers = watchers == null ? List.of() : List.copyOf(watchers);
    }
}
