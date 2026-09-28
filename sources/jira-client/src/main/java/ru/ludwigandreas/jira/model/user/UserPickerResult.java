package ru.ludwigandreas.jira.model.user;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * The response of the user picker endpoint, which answers a type-ahead box rather than a search.
 *
 * <p>{@code total} counts every match; {@code users} holds only the first {@code maxResults} of them, and
 * each carries pre-highlighted HTML in {@code html}. Use it to populate a picker, not to enumerate users -
 * {@link ru.ludwigandreas.jira.api.UserApi#search} is the enumerating endpoint.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserPickerResult(List<PickedUser> users, Integer total, String header) {

    /** One suggestion from the picker, with the HTML fragment Jira rendered for it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PickedUser(String name, String key, String html, String displayName, String avatarUrl) {
    }
}
