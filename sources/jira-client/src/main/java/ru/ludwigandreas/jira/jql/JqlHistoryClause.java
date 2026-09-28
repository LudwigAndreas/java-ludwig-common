package ru.ludwigandreas.jira.jql;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A JQL clause over an issue's change history - {@code WAS}, {@code WAS IN}, {@code CHANGED} - with the
 * predicates that narrow it.
 *
 * <p>These are the only way to ask "was this ever" rather than "is this now", and they are what turns a
 * status field into a cycle-time query: {@code status CHANGED TO "Done" AFTER startOfWeek()}. Each method
 * returns a new clause, so a partially built one can be shared.
 *
 * <p>Predicate order is not arbitrary in JQL - {@code FROM}/{@code TO} must precede {@code BY} and the
 * temporal predicates - so this class keeps them in the order they were added and the factory methods on
 * {@link ValueJqlField} add {@code FROM}/{@code TO} first.
 */
public final class JqlHistoryClause implements JqlClause {

    private final String head;
    private final List<String> predicates;

    JqlHistoryClause(String head, List<String> predicates) {
        this.head = head;
        this.predicates = List.copyOf(predicates);
    }

    /** Restricts to changes made by a named user. */
    public JqlHistoryClause by(String username) {
        return with("BY " + JqlValue.quote(username));
    }

    /** Restricts to changes made by the calling user. */
    public JqlHistoryClause byCurrentUser() {
        return with("BY " + JqlFunctions.currentUser().render());
    }

    /** Restricts to changes made by any member of a group. */
    public JqlHistoryClause byMembersOf(String groupName) {
        return with("BY " + JqlFunctions.membersOf(groupName).render());
    }

    /** Restricts to changes made before a point in time. */
    public JqlHistoryClause before(JqlValue when) {
        return with("BEFORE " + when.render());
    }

    /** Restricts to changes made before a date. */
    public JqlHistoryClause before(LocalDate when) {
        return before(JqlValue.of(when));
    }

    /** Restricts to changes made after a point in time. */
    public JqlHistoryClause after(JqlValue when) {
        return with("AFTER " + when.render());
    }

    /** Restricts to changes made after a date. */
    public JqlHistoryClause after(LocalDate when) {
        return after(JqlValue.of(when));
    }

    /** Restricts to changes made on a given day. */
    public JqlHistoryClause on(LocalDate day) {
        return with("ON " + JqlValue.of(day).render());
    }

    /** Restricts to changes made within a window, inclusive at both ends. */
    public JqlHistoryClause during(JqlValue from, JqlValue to) {
        return with("DURING (" + from.render() + ", " + to.render() + ")");
    }

    /** Restricts to changes whose previous value was the given one. */
    public JqlHistoryClause from(String previousValue) {
        return with("FROM " + JqlValue.quote(previousValue));
    }

    /** Restricts to changes whose new value was the given one. */
    public JqlHistoryClause to(String newValue) {
        return with("TO " + JqlValue.quote(newValue));
    }

    private JqlHistoryClause with(String predicate) {
        List<String> extended = new ArrayList<>(predicates);
        extended.add(predicate);
        return new JqlHistoryClause(head, extended);
    }

    @Override
    public String render() {
        return predicates.isEmpty() ? head : head + " " + String.join(" ", predicates);
    }

    @Override
    public String renderNested() {
        return render();
    }
}
