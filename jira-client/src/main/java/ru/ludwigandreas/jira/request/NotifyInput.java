package ru.ludwigandreas.jira.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * The payload for {@code POST /issue/{key}/notify}: send an ad-hoc email about an issue.
 *
 * <p>Useful for an integration that needs to tell people something Jira's own notification scheme will not -
 * an SLA breach, a deployment that shipped their fix. It respects the project's permission scheme: a
 * recipient who cannot browse the issue does not receive the mail, silently.
 *
 * @param subject email subject
 * @param textBody plain-text body
 * @param htmlBody HTML body
 * @param to recipients, as Jira's {@code to} object
 * @param restrict optional restriction to groups or permissions
 */
public record NotifyInput(@JsonProperty("subject") String subject,
                          @JsonProperty("textBody") String textBody,
                          @JsonProperty("htmlBody") String htmlBody,
                          @JsonProperty("to") Map<String, Object> to,
                          @JsonProperty("restrict") Map<String, Object> restrict) {

    /** A plain-text notification to named users. */
    public static NotifyInput toUsers(String subject, String body, List<String> usernames) {
        List<Map<String, String>> users = usernames.stream().map(name -> Map.of("name", name)).toList();
        return new NotifyInput(subject, body, null, Map.of("users", users), null);
    }

    /** A plain-text notification to everyone Jira would normally notify about this issue. */
    public static NotifyInput toWatchersAndAssignee(String subject, String body) {
        return new NotifyInput(subject, body, null,
                Map.of("watchers", Boolean.TRUE, "assignee", Boolean.TRUE, "reporter", Boolean.TRUE), null);
    }
}
