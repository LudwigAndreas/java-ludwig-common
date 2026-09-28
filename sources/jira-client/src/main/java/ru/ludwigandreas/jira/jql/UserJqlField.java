package ru.ludwigandreas.jira.jql;

/**
 * A JQL field holding a user: assignee, reporter, creator, watcher, voter, or a user custom field.
 *
 * <p>Adds the two idioms that make up most user clauses. Note that on Jira Server the value is the
 * <em>username</em>, not an email address and not an account id - {@code assignee = "jsmith"}, never
 * {@code assignee = "jsmith@example.com"} unless usernames on that instance happen to be addresses.
 */
public final class UserJqlField extends ValueJqlField {

    /**
     * Creates a reference to a field of this kind.
     *
     * @param name the field's JQL name
     */
    public UserJqlField(String name) {
        super(name);
    }

    /** {@code field = currentUser()}. */
    public JqlClause isCurrentUser() {
        return is(JqlFunctions.currentUser());
    }

    /** {@code field != currentUser()}. */
    public JqlClause isNotCurrentUser() {
        return binary("!=", JqlFunctions.currentUser().asValue());
    }

    /** {@code field IN membersOf("group")}. */
    public JqlClause inGroup(String groupName) {
        return in(JqlFunctions.membersOf(groupName));
    }

    /** {@code field IS EMPTY}, spelled for the field it is usually asked of. */
    public JqlClause isUnassigned() {
        return isEmpty();
    }
}
