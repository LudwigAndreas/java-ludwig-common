package ru.ludwigandreas.jira.model.filter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import ru.ludwigandreas.jira.model.user.JiraUser;

/**
 * A saved JQL filter.
 *
 * <p>{@code jql} is the stored query text. Resolving it into issues means running it through the search
 * endpoint; the {@code searchUrl} Jira returns points at the same thing but is only usable with the
 * client's own credentials attached.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Filter(String self,
                     String id,
                     String name,
                     String description,
                     JiraUser owner,
                     String jql,
                     String viewUrl,
                     String searchUrl,
                     Boolean favourite,
                     List<SharePermission> sharePermissions,
                     List<Subscription> subscriptions) {

    /** Normalizes the two collections to immutable empty lists rather than {@code null}. */
    public Filter {
        sharePermissions = sharePermissions == null ? List.of() : List.copyOf(sharePermissions);
        subscriptions = subscriptions == null ? List.of() : List.copyOf(subscriptions);
    }
}
