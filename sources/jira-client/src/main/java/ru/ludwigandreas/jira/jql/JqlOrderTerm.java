package ru.ludwigandreas.jira.jql;

/**
 * One term of a JQL {@code ORDER BY}: a field and a direction.
 *
 * <p>Ordering is not cosmetic when a query is going to be paged. Jira's offset pagination reads a moving
 * result set, so a walk ordered by {@code updated} - which changes while the walk is in progress - can
 * return an issue twice and skip another. Ordering by a key that does not change under you
 * ({@code ORDER BY created ASC, key ASC}) is what makes a paged export complete.
 */
public record JqlOrderTerm(String field, Direction direction) {

    /** Sort direction. */
    public enum Direction {

        /** Ascending. */
        ASC,

        /** Descending. */
        DESC
    }

    /** The rendered term, for example {@code created DESC}. */
    public String render() {
        return field + " " + direction.name();
    }
}
