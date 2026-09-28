package ru.ludwigandreas.jira.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import ru.ludwigandreas.jira.model.filter.SharePermission;

/**
 * The payload for creating or updating a saved filter.
 *
 * <p>A filter created with no {@code sharePermissions} is private to the account that created it - which,
 * for a filter created by an integration's service account, means nobody on the team can see it. Set the
 * share permissions at creation time; adding them afterwards is a second call that is easy to forget.
 *
 * @param name filter name
 * @param description free text
 * @param jql the query
 * @param favourite whether to mark it a favourite for the owner
 * @param sharePermissions who the filter is shared with
 */
public record FilterInput(@JsonProperty("name") String name,
                          @JsonProperty("description") String description,
                          @JsonProperty("jql") String jql,
                          @JsonProperty("favourite") Boolean favourite,
                          @JsonProperty("sharePermissions") List<SharePermission> sharePermissions) {

    /** A private filter. */
    public static FilterInput of(String name, String jql) {
        return new FilterInput(name, null, jql, null, null);
    }

    /** A filter shared with a group. */
    public static FilterInput sharedWithGroup(String name, String jql, String groupName) {
        return new FilterInput(name, null, jql, null, List.of(SharePermission.group(groupName)));
    }
}
