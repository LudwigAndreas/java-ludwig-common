package ru.ludwigandreas.jira.jql;

/**
 * Entry point to the JQL builder: the system fields as typed references, plus factories for custom fields.
 *
 * <p>Written to read like the query it produces:
 *
 * <pre>{@code
 * String jql = JqlQuery.builder()
 *         .where(Jql.project().in("OPS", "PLAT"))
 *         .where(Jql.status().notIn("Done", "Closed"))
 *         .where(Jql.assignee().isCurrentUser())
 *         .where(Jql.created().after("-30d"))
 *         .where(Jql.customFieldByName("Story Points").atLeast(3))
 *         .orderByStableWalk()
 *         .render();
 * }</pre>
 *
 * <p>Every value that goes in is escaped, so a project key or a label taken from user input cannot break
 * out of its literal and alter the query - see {@link JqlValue}.
 *
 * <p>Two ways to name a custom field, and the choice matters.
 * {@link #customFieldById(long)} renders {@code cf[10004]}, which is stable: it keeps working when an
 * administrator renames the field. {@link #customFieldByName(String)} renders {@code "Story Points"}, which
 * is readable but breaks on a rename and is ambiguous when two custom fields share a display name - Jira
 * answers such a query with a parse error naming the ambiguity. Prefer the id in code that has to keep
 * running; resolve a name to an id once with
 * {@link ru.ludwigandreas.jira.field.CustomFieldRegistry} and use the id thereafter.
 */
public final class Jql {

    private Jql() {
    }

    /** The {@code project} field. */
    public static ValueJqlField project() {
        return new ValueJqlField("project");
    }

    /** The {@code issuekey} field; also accepts {@code key}. */
    public static ValueJqlField key() {
        return new ValueJqlField("issuekey");
    }

    /** The {@code id} field, matching the numeric issue id. */
    public static ValueJqlField id() {
        return new ValueJqlField("id");
    }

    /** The {@code issuetype} field. */
    public static ValueJqlField issueType() {
        return new ValueJqlField("issuetype");
    }

    /** The {@code status} field. */
    public static ValueJqlField status() {
        return new ValueJqlField("status");
    }

    /** The {@code statusCategory} field - the stable To Do / In Progress / Done grouping. */
    public static ValueJqlField statusCategory() {
        return new ValueJqlField("statusCategory");
    }

    /** The {@code resolution} field. */
    public static ValueJqlField resolution() {
        return new ValueJqlField("resolution");
    }

    /** The {@code priority} field. */
    public static ValueJqlField priority() {
        return new ValueJqlField("priority");
    }

    /** The {@code labels} field. */
    public static ValueJqlField labels() {
        return new ValueJqlField("labels");
    }

    /** The {@code component} field. */
    public static ValueJqlField component() {
        return new ValueJqlField("component");
    }

    /** The {@code fixVersion} field. */
    public static ValueJqlField fixVersion() {
        return new ValueJqlField("fixVersion");
    }

    /** The {@code affectedVersion} field. */
    public static ValueJqlField affectedVersion() {
        return new ValueJqlField("affectedVersion");
    }

    /** The {@code category} field - the project category. */
    public static ValueJqlField projectCategory() {
        return new ValueJqlField("category");
    }

    /** The {@code parent} field. */
    public static ValueJqlField parent() {
        return new ValueJqlField("parent");
    }

    /** The {@code filter} field, matching the issues of a saved filter. */
    public static ValueJqlField filter() {
        return new ValueJqlField("filter");
    }

    /** The {@code level} field - the issue security level. */
    public static ValueJqlField securityLevel() {
        return new ValueJqlField("level");
    }

    /** The {@code assignee} field. */
    public static UserJqlField assignee() {
        return new UserJqlField("assignee");
    }

    /** The {@code reporter} field. */
    public static UserJqlField reporter() {
        return new UserJqlField("reporter");
    }

    /** The {@code creator} field. */
    public static UserJqlField creator() {
        return new UserJqlField("creator");
    }

    /** The {@code watcher} field. */
    public static UserJqlField watcher() {
        return new UserJqlField("watcher");
    }

    /** The {@code voter} field. */
    public static UserJqlField voter() {
        return new UserJqlField("voter");
    }

    /** The {@code summary} field. */
    public static TextJqlField summary() {
        return new TextJqlField("summary");
    }

    /** The {@code description} field. */
    public static TextJqlField description() {
        return new TextJqlField("description");
    }

    /** The {@code environment} field. */
    public static TextJqlField environment() {
        return new TextJqlField("environment");
    }

    /** The {@code comment} field, searched through the text index. */
    public static TextJqlField comment() {
        return new TextJqlField("comment");
    }

    /** The synthetic {@code text} field, which spans summary, description, environment and comments. */
    public static TextJqlField text() {
        return new TextJqlField("text");
    }

    /** The {@code created} field. */
    public static DateJqlField created() {
        return new DateJqlField("created");
    }

    /** The {@code updated} field. */
    public static DateJqlField updated() {
        return new DateJqlField("updated");
    }

    /** The {@code resolved} field. */
    public static DateJqlField resolved() {
        return new DateJqlField("resolved");
    }

    /** The {@code due} field. */
    public static DateJqlField dueDate() {
        return new DateJqlField("due");
    }

    /** The {@code lastViewed} field. */
    public static DateJqlField lastViewed() {
        return new DateJqlField("lastViewed");
    }

    /** The {@code votes} field. */
    public static NumberJqlField votes() {
        return new NumberJqlField("votes");
    }

    /** The {@code watchers} field. */
    public static NumberJqlField watchers() {
        return new NumberJqlField("watchers");
    }

    /** The {@code workRatio} field. */
    public static NumberJqlField workRatio() {
        return new NumberJqlField("workRatio");
    }

    /** The {@code timeSpent} field, in seconds. */
    public static NumberJqlField timeSpent() {
        return new NumberJqlField("timeSpent");
    }

    /** The {@code originalEstimate} field, in seconds. */
    public static NumberJqlField originalEstimate() {
        return new NumberJqlField("originalEstimate");
    }

    /** The {@code remainingEstimate} field, in seconds. */
    public static NumberJqlField remainingEstimate() {
        return new NumberJqlField("remainingEstimate");
    }

    /**
     * A custom field addressed by its numeric id, rendering {@code cf[10004]}.
     *
     * <p>The form to prefer in code: it survives the field being renamed, and it is unambiguous when two
     * custom fields share a display name.
     *
     * @param customFieldId the numeric part of {@code customfield_10004}
     * @return a value-typed reference; use the other {@code customField...} methods for a typed one
     */
    public static ValueJqlField customFieldById(long customFieldId) {
        return new ValueJqlField("cf[" + customFieldId + "]");
    }

    /** A number custom field by id. */
    public static NumberJqlField numberCustomFieldById(long customFieldId) {
        return new NumberJqlField("cf[" + customFieldId + "]");
    }

    /** A date custom field by id. */
    public static DateJqlField dateCustomFieldById(long customFieldId) {
        return new DateJqlField("cf[" + customFieldId + "]");
    }

    /** A text custom field by id. */
    public static TextJqlField textCustomFieldById(long customFieldId) {
        return new TextJqlField("cf[" + customFieldId + "]");
    }

    /** A user custom field by id. */
    public static UserJqlField userCustomFieldById(long customFieldId) {
        return new UserJqlField("cf[" + customFieldId + "]");
    }

    /** A custom field addressed by display name, quoted if it needs to be. */
    public static ValueJqlField customFieldByName(String name) {
        return new ValueJqlField(name);
    }

    /** A number custom field by display name. */
    public static NumberJqlField numberCustomFieldByName(String name) {
        return new NumberJqlField(name);
    }

    /** A date custom field by display name. */
    public static DateJqlField dateCustomFieldByName(String name) {
        return new DateJqlField(name);
    }

    /** A text custom field by display name. */
    public static TextJqlField textCustomFieldByName(String name) {
        return new TextJqlField(name);
    }

    /** A user custom field by display name. */
    public static UserJqlField userCustomFieldByName(String name) {
        return new UserJqlField(name);
    }
}
