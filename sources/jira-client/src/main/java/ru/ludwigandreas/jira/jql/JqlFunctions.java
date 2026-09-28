package ru.ludwigandreas.jira.jql;

/**
 * The JQL functions Jira Server 9.12 ships, as typed factories.
 *
 * <p>Grouped by what they return, because a function is only legal on a field of a matching type: a user
 * function on a user field, a version function on a version field. Jira reports a mismatch as a parse
 * error at query time, which is the sort of thing this library exists to turn into a compile error - hence
 * the typed {@code JqlField} hierarchy that consumes these.
 *
 * <p>Deliberately absent are the Jira Software functions ({@code openSprints()}, {@code closedSprints()},
 * {@code futureSprints()}) and the Service Management ones ({@code myApproval()}, {@code approved()}).
 * They are perfectly valid JQL on an instance carrying those applications, but they are not part of Jira
 * Platform and this module's declared scope stops at the platform REST API. Reach them through
 * {@link JqlFunction#of(String, String...)}, which renders any function name with escaped arguments.
 */
public final class JqlFunctions {

    private JqlFunctions() {
    }

    /** The user the request is authenticated as. */
    public static JqlFunction currentUser() {
        return JqlFunction.raw("currentUser");
    }

    /** Every member of a group, transitively through nested groups. */
    public static JqlFunction membersOf(String groupName) {
        return JqlFunction.of("membersOf", groupName);
    }

    /** The current instant. */
    public static JqlFunction now() {
        return JqlFunction.raw("now");
    }

    /** Midnight at the start of today, optionally offset - {@code startOfDay("-3d")}. */
    public static JqlFunction startOfDay(String offset) {
        return offset == null ? JqlFunction.raw("startOfDay") : JqlFunction.of("startOfDay", offset);
    }

    /** The last instant of today, optionally offset. */
    public static JqlFunction endOfDay(String offset) {
        return offset == null ? JqlFunction.raw("endOfDay") : JqlFunction.of("endOfDay", offset);
    }

    /** The start of the current week, as the instance defines it, optionally offset. */
    public static JqlFunction startOfWeek(String offset) {
        return offset == null ? JqlFunction.raw("startOfWeek") : JqlFunction.of("startOfWeek", offset);
    }

    /** The end of the current week, optionally offset. */
    public static JqlFunction endOfWeek(String offset) {
        return offset == null ? JqlFunction.raw("endOfWeek") : JqlFunction.of("endOfWeek", offset);
    }

    /** The start of the current month, optionally offset. */
    public static JqlFunction startOfMonth(String offset) {
        return offset == null ? JqlFunction.raw("startOfMonth") : JqlFunction.of("startOfMonth", offset);
    }

    /** The end of the current month, optionally offset. */
    public static JqlFunction endOfMonth(String offset) {
        return offset == null ? JqlFunction.raw("endOfMonth") : JqlFunction.of("endOfMonth", offset);
    }

    /** The start of the current year, optionally offset. */
    public static JqlFunction startOfYear(String offset) {
        return offset == null ? JqlFunction.raw("startOfYear") : JqlFunction.of("startOfYear", offset);
    }

    /** The end of the current year, optionally offset. */
    public static JqlFunction endOfYear(String offset) {
        return offset == null ? JqlFunction.raw("endOfYear") : JqlFunction.of("endOfYear", offset);
    }

    /** The calling user's current session start. */
    public static JqlFunction currentLogin() {
        return JqlFunction.raw("currentLogin");
    }

    /** The start of the calling user's previous session. */
    public static JqlFunction lastLogin() {
        return JqlFunction.raw("lastLogin");
    }

    /** Issues the calling user has browsed recently. */
    public static JqlFunction issueHistory() {
        return JqlFunction.raw("issueHistory");
    }

    /** Issues the calling user has voted for. */
    public static JqlFunction votedIssues() {
        return JqlFunction.raw("votedIssues");
    }

    /** Issues the calling user watches. */
    public static JqlFunction watchedIssues() {
        return JqlFunction.raw("watchedIssues");
    }

    /** Issues linked to the given issue, over any link type. */
    public static JqlFunction linkedIssues(String issueKey) {
        return JqlFunction.of("linkedIssues", issueKey);
    }

    /** Issues linked to the given issue over one named link type. */
    public static JqlFunction linkedIssues(String issueKey, String linkType) {
        return JqlFunction.of("linkedIssues", issueKey, linkType);
    }

    /** Projects the calling user leads. */
    public static JqlFunction projectsLeadByUser() {
        return JqlFunction.raw("projectsLeadByUser");
    }

    /** Projects in which the calling user holds the named permission. */
    public static JqlFunction projectsWhereUserHasPermission(String permission) {
        return JqlFunction.of("projectsWhereUserHasPermission", permission);
    }

    /** Projects in which the calling user holds the named role. */
    public static JqlFunction projectsWhereUserHasRole(String role) {
        return JqlFunction.of("projectsWhereUserHasRole", role);
    }

    /** Components the calling user leads. */
    public static JqlFunction componentsLeadByUser() {
        return JqlFunction.raw("componentsLeadByUser");
    }

    /** The earliest unreleased version of a project - the usual "next release" clause. */
    public static JqlFunction earliestUnreleasedVersion(String projectKey) {
        return JqlFunction.of("earliestUnreleasedVersion", projectKey);
    }

    /** The most recently released version of a project. */
    public static JqlFunction latestReleasedVersion(String projectKey) {
        return JqlFunction.of("latestReleasedVersion", projectKey);
    }

    /** Every released version of a project. */
    public static JqlFunction releasedVersions(String projectKey) {
        return JqlFunction.of("releasedVersions", projectKey);
    }

    /** Every unreleased version of a project. */
    public static JqlFunction unreleasedVersions(String projectKey) {
        return JqlFunction.of("unreleasedVersions", projectKey);
    }

    /** Every issue type that is not a subtask type. */
    public static JqlFunction standardIssueTypes() {
        return JqlFunction.raw("standardIssueTypes");
    }

    /** Every subtask issue type. */
    public static JqlFunction subtaskIssueTypes() {
        return JqlFunction.raw("subtaskIssueTypes");
    }
}
