package ru.ludwigandreas.jira.model.user;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;
import ru.ludwigandreas.jira.model.common.ItemList;
import ru.ludwigandreas.jira.model.common.JiraGroup;

/**
 * A Jira Server user.
 *
 * <p>The identity field is {@code name} - the username - and secondarily {@code key}, the immutable internal
 * id that survives a username change. Jira Cloud's {@code accountId} does not exist on Server 9.12 and is
 * absent from this model on purpose: every write in this client that names a user sends {@code name}, which
 * is what 9.12 accepts.
 *
 * <p>{@code emailAddress} is {@code null} whenever the instance's user privacy setting hides it, not only
 * when the account has no address. Do not treat an absent address as "no address".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JiraUser(String self,
                       String key,
                       String name,
                       String emailAddress,
                       String displayName,
                       Boolean active,
                       Boolean deleted,
                       String timeZone,
                       String locale,
                       Map<String, String> avatarUrls,
                       ItemList<JiraGroup> groups,
                       ItemList<ApplicationRole> applicationRoles,
                       String expand) {

    /** A reference carrying only the username, which is all a Jira Server write payload needs. */
    public static JiraUser named(String username) {
        return new JiraUser(null, null, username, null, null, null, null, null, null, null, null, null, null);
    }
}
