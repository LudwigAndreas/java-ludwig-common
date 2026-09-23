package ru.ludwigandreas.jira.jql;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A complete JQL query: an optional condition plus an optional ordering.
 *
 * <p>Both halves are optional because both are optional in JQL. A query with no condition
 * ({@code ORDER BY created DESC} on its own) is legal and means "every issue you can see", which is
 * occasionally what an export wants and is otherwise a mistake worth noticing - hence
 * {@link #isUnbounded()}.
 */
public final class JqlQuery {

    private final JqlClause where;
    private final List<JqlOrderTerm> orderBy;

    private JqlQuery(JqlClause where, List<JqlOrderTerm> orderBy) {
        this.where = where;
        this.orderBy = List.copyOf(orderBy);
    }

    /** A builder for a query. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Wraps a hand-written JQL string.
     *
     * <p>The one supported way to run a query this builder cannot express - a filter's stored JQL, a string
     * a user typed into a search box. It is not validated here; Jira validates it and reports a parse error
     * as a 400.
     *
     * @param jql the query text
     * @return a query that renders exactly this text
     */
    public static JqlQuery of(String jql) {
        return new JqlQuery(JqlClause.raw(jql), List.of());
    }

    /** The condition, or {@code null} when the query has none. */
    public JqlClause where() {
        return where;
    }

    /** The ordering terms, in order. */
    public List<JqlOrderTerm> orderBy() {
        return orderBy;
    }

    /** Whether this query has no condition and therefore matches every visible issue. */
    public boolean isUnbounded() {
        return where == null;
    }

    /** The rendered JQL. */
    public String render() {
        StringBuilder out = new StringBuilder();
        if (where != null) {
            out.append(where.render());
        }
        if (!orderBy.isEmpty()) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append("ORDER BY ")
                    .append(orderBy.stream().map(JqlOrderTerm::render).collect(Collectors.joining(", ")));
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return render();
    }

    /** Fluent builder for {@link JqlQuery}. */
    public static final class Builder {

        private final List<JqlClause> clauses = new ArrayList<>();
        private final List<JqlOrderTerm> orderBy = new ArrayList<>();

        private Builder() {
        }

        /** Adds a condition; several calls are combined with {@code AND}. */
        public Builder where(JqlClause clause) {
            clauses.add(clause);
            return this;
        }

        /** Adds a condition only when the guard holds, for building a query from optional filter inputs. */
        public Builder whereIf(boolean condition, JqlClause clause) {
            if (condition) {
                clauses.add(clause);
            }
            return this;
        }

        /** Appends ordering terms. */
        public Builder orderBy(JqlOrderTerm... terms) {
            orderBy.addAll(Arrays.asList(terms));
            return this;
        }

        /**
         * Appends the ordering that makes a paged walk safe: oldest first, tie-broken on the issue key.
         *
         * <p>Worth preferring over {@code ORDER BY updated DESC} for anything that pages, because
         * {@code created} and {@code key} do not change while the walk is in progress, and an offset-paged
         * read of a result set that reorders under you loses rows. See {@link ru.ludwigandreas.jira.page.PageRequest}.
         *
         * @return this builder
         */
        public Builder orderByStableWalk() {
            orderBy.add(Jql.created().asc());
            orderBy.add(Jql.key().asc());
            return this;
        }

        /** Builds the query, combining every condition with {@code AND}. */
        public JqlQuery build() {
            JqlClause combined = clauses.isEmpty() ? null : JqlClause.allOf(clauses);
            return new JqlQuery(combined, orderBy);
        }

        /** Builds the query and renders it, for the common case of wanting the string. */
        public String render() {
            return build().render();
        }
    }
}
