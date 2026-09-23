package ru.ludwigandreas.jira.model.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import ru.ludwigandreas.jira.model.user.JiraUser;

/**
 * The {@code votes} field on an issue.
 *
 * @param self absolute URL of the votes resource
 * @param votes number of votes
 * @param hasVoted whether the calling user has voted
 * @param voters the voters, populated only by the dedicated votes endpoint
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Votes(String self, Integer votes, Boolean hasVoted, List<JiraUser> voters) {

    /** Normalizes {@code voters} to an immutable empty list rather than {@code null}. */
    public Votes {
        voters = voters == null ? List.of() : List.copyOf(voters);
    }
}
