package ru.ludwigandreas.jira.field;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import ru.ludwigandreas.jira.json.JiraDateTimeFormats;
import ru.ludwigandreas.jira.model.field.CustomFieldOption;

/**
 * Builds the value shapes Jira expects when <em>writing</em> a custom field.
 *
 * <p>The write shapes are not the read shapes, and that catches people out. A select field reads back as
 * {@code {"self":..., "value":"Blue", "id":"10100"}} but is written as {@code {"id":"10100"}} alone; a user
 * picker reads back a full user object and is written as {@code {"name":"jsmith"}}. Sending the object that
 * came out of a read straight back into a write mostly works and occasionally does not, in ways that depend
 * on the field type - so these factories produce the minimal, documented write shape instead.
 *
 * <p>Used with {@link ru.ludwigandreas.jira.request.IssueInput.Builder#field(String, Object)}:
 *
 * <pre>{@code
 * IssueInput.builder()
 *         .field(storyPoints.id(), 5)
 *         .field(team.id(), FieldValues.option("10100"))
 *         .field(reviewers.id(), FieldValues.users("jsmith", "adoe"))
 *         .build();
 * }</pre>
 */
public final class FieldValues {

    private FieldValues() {
    }

    /** A select, radio or checkbox option, by option id - the form that survives an option being renamed. */
    public static Map<String, String> option(String optionId) {
        return Map.of("id", optionId);
    }

    /** A select option by display value, for the cases where only the text is known. */
    public static Map<String, String> optionByValue(String value) {
        return Map.of("value", value);
    }

    /** Several options by id, for a multi-select or checkbox field. */
    public static List<Map<String, String>> options(String... optionIds) {
        return Arrays.stream(optionIds).map(FieldValues::option).toList();
    }

    /** A cascading-select value: the parent option and the child under it. */
    public static Map<String, Object> cascadingOption(String parentOptionId, String childOptionId) {
        return Map.of("id", parentOptionId, "child", option(childOptionId));
    }

    /** The full cascading option model, for callers that already hold one. */
    public static CustomFieldOption cascading(String parentOptionId, String childOptionId) {
        return CustomFieldOption.cascading(parentOptionId, childOptionId);
    }

    /** A user-picker value. On Jira Server the key is {@code name} - the username - not {@code accountId}. */
    public static Map<String, String> user(String username) {
        return Map.of("name", username);
    }

    /** A multi user-picker value. */
    public static List<Map<String, String>> users(String... usernames) {
        return Arrays.stream(usernames).map(FieldValues::user).toList();
    }

    /** A group-picker value. */
    public static Map<String, String> group(String groupName) {
        return Map.of("name", groupName);
    }

    /** A multi group-picker value. */
    public static List<Map<String, String>> groups(String... groupNames) {
        return Arrays.stream(groupNames).map(FieldValues::group).toList();
    }

    /** A version-picker value, by version id. */
    public static Map<String, String> version(String versionId) {
        return Map.of("id", versionId);
    }

    /** A multi version-picker value. */
    public static List<Map<String, String>> versions(String... versionIds) {
        return Arrays.stream(versionIds).map(FieldValues::version).toList();
    }

    /** A date-picker value: {@code "yyyy-MM-dd"}. */
    public static String date(LocalDate date) {
        return date.toString();
    }

    /** A date-time value in the single format Jira's input parser accepts. */
    public static String dateTime(OffsetDateTime timestamp) {
        return JiraDateTimeFormats.writer().format(timestamp);
    }
}
